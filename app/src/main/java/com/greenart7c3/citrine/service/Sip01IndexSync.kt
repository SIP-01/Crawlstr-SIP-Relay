package com.greenart7c3.citrine.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.server.sip01.WebDocument
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Sip01SyncPhase {
    IDLE,
    SYNCING,
    LISTENING,
    PAUSED,
}

data class Sip01SyncStatus(
    val enabled: Boolean = false,
    val phase: Sip01SyncPhase = Sip01SyncPhase.IDLE,
    val relaysConfigured: Int = 0,
    val relaysConnected: Int = 0,
    val eventsReceived: Long = 0L,
    val lastSyncEpoch: Long = 0L,
)

/**
 * SIP-01 index federation (relay profile §6): subscribes to kind 39697 web index
 * observations on the configured peer index relays and feeds them through the
 * normal ingestion path, where they are signature-checked, SIP-01-validated
 * (§12.4), stored, and indexed — turning the phone into a live replica of the
 * shared decentralized index. NIP-77 negentropy-capable peers can additionally
 * reconcile against us over the same filters.
 *
 * Deliberately simpler than [RelayAggregator]: no author routing (the filter is
 * kinds-only), no NIP-65 lookups, no auth — index relays serve public read access.
 * The `since` watermark persists across restarts so a catch-up subscription only
 * re-fetches the overlap window.
 */
object Sip01IndexSync {
    private const val TAG = "Sip01IndexSync"

    /** Re-fetch overlap on each (re)subscribe so events near the watermark aren't lost. */
    private const val OVERLAP_SEC = 5L * 60L

    /** Cold-start catch-up window when no watermark exists yet. */
    private const val INITIAL_CATCHUP_WINDOW_SEC = 7L * 24L * 60L * 60L

    /** Bounded hand-off buffer; DROP_OLDEST so a chatty relay can't OOM the IO pool. */
    private const val EVENT_CHANNEL_CAPACITY = 256

    /** Collapse bursts of connectivity callbacks into one evaluation. */
    private const val NETWORK_DEBOUNCE_MS = 2_000L

    /** Persist the sync watermark at most this often. */
    private const val WATERMARK_FLUSH_INTERVAL_MS = 60_000L

    @Volatile private var scope: CoroutineScope? = null

    @Volatile private var eventChannel: Channel<Event>? = null

    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var pausedForNetwork: Boolean = false

    private val trackedSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val subscribedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    private val connectedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()

    private val eventsReceived = AtomicLong(0)
    private val maxObservedAt = AtomicLong(0)
    private val watermarkDirty = AtomicBoolean(false)

    private var listener: RelayConnectionListener? = null

    private val _status = MutableStateFlow(Sip01SyncStatus())
    val status: StateFlow<Sip01SyncStatus> = _status.asStateFlow()

    @Synchronized
    fun start(database: AppDatabase) {
        if (scope != null) return
        if (!Settings.sip01Enabled || !Settings.sip01SyncEnabled) return
        if (Settings.sip01IndexRelays.isEmpty()) return

        Log.d(TAG, "Starting SIP-01 index sync from ${Settings.sip01IndexRelays.size} relays")
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        eventsReceived.set(0)
        maxObservedAt.set(Settings.sip01SyncLastSync)

        val newEventChannel = Channel<Event>(EVENT_CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
        eventChannel = newEventChannel

        // Single consumer for inbound events: persist sequentially. Ingestion runs the
        // full verification + SIP-01 validation + indexing path via the server.
        newScope.launch {
            for (ev in newEventChannel) {
                try {
                    if (ev.kind != WebDocument.WEB_DOCUMENT_KIND) continue
                    CustomWebSocketService.server?.innerProcessEvent(ev, null, fromAggregator = true)
                    maxObservedAt.updateMax(ev.createdAt)
                    watermarkDirty.set(true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "innerProcessEvent failed", e)
                }
            }
        }

        // Periodic watermark flush: advances Settings.sip01SyncLastSync to the newest
        // observation time actually ingested, so restarts resume where we left off.
        newScope.launch {
            while (isActive) {
                delay(WATERMARK_FLUSH_INTERVAL_MS)
                flushWatermark()
            }
        }

        val newListener = SyncListener()
        listener = newListener
        Citrine.instance.client.addConnectionListener(newListener)

        registerNetworkCallback()
        evaluateNetworkState()

        if (!pausedForNetwork) {
            subscribeAll()
        }

        publishStatus()
    }

    @Synchronized
    fun stop() {
        val s = scope ?: return
        Log.d(TAG, "Stopping SIP-01 index sync")
        listener?.let { Citrine.instance.client.removeConnectionListener(it) }
        listener = null
        unregisterNetworkCallback()

        trackedSubIds.forEach { runCatching { Citrine.instance.client.unsubscribe(it) } }
        trackedSubIds.clear()
        subscribedRelays.clear()
        connectedRelays.clear()
        pausedForNetwork = false

        eventChannel?.close()
        eventChannel = null

        scope = null
        s.cancel()

        flushWatermark()
        _status.value = Sip01SyncStatus(enabled = false, phase = Sip01SyncPhase.IDLE, lastSyncEpoch = Settings.sip01SyncLastSync)
    }

    /**
     * Called from settings when SIP-01 configuration changes: starts or stops the
     * sync, or rebuilds subscriptions when the relay set changed.
     */
    fun onConfigChanged(database: AppDatabase) {
        val enabled = Settings.sip01Enabled && Settings.sip01SyncEnabled && Settings.sip01IndexRelays.isNotEmpty()
        val running = scope != null
        when {
            enabled && !running -> start(database)
            !enabled && running -> stop()
            enabled && running -> {
                evaluateNetworkState()
                if (!pausedForNetwork) subscribeAll()
            }
        }
    }

    /**
     * (Re)issue one subscription per configured index relay: all kind 39697 events
     * since the persisted watermark (with overlap). Subscriptions are idempotent in
     * quartz — the client diffs desired vs actual subs per relay on (re)connect.
     */
    private fun subscribeAll() {
        val relays = Settings.sip01IndexRelays.mapNotNull { raw ->
            runCatching { RelayUrlNormalizer.normalize(raw) }.getOrNull()
        }.toSet()
        if (relays.isEmpty()) return

        // Rebuild from scratch on every invocation (config change, network resume):
        // drop the previous subscriptions so sub ids don't accumulate in the client.
        trackedSubIds.forEach { runCatching { Citrine.instance.client.unsubscribe(it) } }
        trackedSubIds.clear()
        subscribedRelays.clear()
        connectedRelays.clear()

        val since = (Settings.sip01SyncLastSync - OVERLAP_SEC)
            .let { if (it > 0) it else nowSeconds() - INITIAL_CATCHUP_WINDOW_SEC }
        val filter = Filter(
            kinds = listOf(WebDocument.WEB_DOCUMENT_KIND),
            since = since,
        )

        publishStatus(phase = Sip01SyncPhase.SYNCING)

        for (relay in relays) {
            val id = newSubId()
            trackedSubIds.add(id)
            subscribedRelays.add(relay)
            runCatching {
                Citrine.instance.client.subscribe(id, mapOf(relay to listOf(filter)))
            }.onFailure { Log.e(TAG, "subscribe to ${relay.url} failed", it) }
        }

        publishStatus()
    }

    private fun flushWatermark() {
        if (!watermarkDirty.compareAndSet(true, false)) return
        val max = maxObservedAt.get()
        if (max > Settings.sip01SyncLastSync) {
            Settings.sip01SyncLastSync = max
            runCatching {
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
            }.onFailure { Log.e(TAG, "Failed to persist sync watermark", it) }
        }
    }

    private fun publishStatus(phase: Sip01SyncPhase? = null) {
        _status.value = Sip01SyncStatus(
            enabled = true,
            phase = phase ?: if (pausedForNetwork) {
                Sip01SyncPhase.PAUSED
            } else if (connectedRelays.isNotEmpty()) {
                Sip01SyncPhase.LISTENING
            } else {
                Sip01SyncPhase.SYNCING
            },
            relaysConfigured = Settings.sip01IndexRelays.size,
            relaysConnected = connectedRelays.size,
            eventsReceived = eventsReceived.get(),
            lastSyncEpoch = Settings.sip01SyncLastSync,
        )
    }

    private fun connectivityManager(): ConnectivityManager? = Citrine.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun isOnMeteredNetwork(): Boolean {
        val cm = connectivityManager() ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isOnLimitedNetwork(): Boolean {
        val cm = connectivityManager() ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = connectivityManager() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkEval()
            override fun onLost(network: Network) = scheduleNetworkEval()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = scheduleNetworkEval()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.e(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = connectivityManager() ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
            .onFailure { Log.e(TAG, "unregisterNetworkCallback failed", it) }
    }

    @Volatile private var networkEvalJob: kotlinx.coroutines.Job? = null

    private fun scheduleNetworkEval() {
        val s = scope ?: return
        networkEvalJob?.cancel()
        networkEvalJob = s.launch {
            delay(NETWORK_DEBOUNCE_MS)
            evaluateNetworkState()
        }
    }

    /**
     * Reconciles the current network state against the wifi-only and
     * pause-on-limited settings, pausing or resuming the sync accordingly.
     */
    @Synchronized
    private fun evaluateNetworkState() {
        if (scope == null) return
        val shouldPause = (Settings.sip01SyncWifiOnly && isOnMeteredNetwork()) ||
            (Settings.sip01SyncPauseOnLimitedNetwork && isOnLimitedNetwork())
        if (shouldPause && !pausedForNetwork) {
            Log.d(TAG, "Pausing SIP-01 index sync: network pause condition triggered")
            pausedForNetwork = true
            trackedSubIds.forEach { runCatching { Citrine.instance.client.unsubscribe(it) } }
            trackedSubIds.clear()
            subscribedRelays.clear()
            connectedRelays.clear()
            publishStatus(Sip01SyncPhase.PAUSED)
        } else if (!shouldPause && pausedForNetwork) {
            Log.d(TAG, "Resuming SIP-01 index sync: network pause condition cleared")
            pausedForNetwork = false
            subscribeAll()
        }
    }

    private class SyncListener : RelayConnectionListener {
        override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
            if (subscribedRelays.contains(relay.url) && connectedRelays.add(relay.url)) {
                Log.d(TAG, "Index relay live (${connectedRelays.size}/${subscribedRelays.size}): ${relay.url.url}")
                publishStatus()
            }
            if (msg is EventMessage && trackedSubIds.contains(msg.subId)) {
                val ev = msg.event
                if (ev.kind != WebDocument.WEB_DOCUMENT_KIND) return
                val n = eventsReceived.incrementAndGet()
                if (n == 1L || n % 500L == 0L) {
                    Log.d(TAG, "Index observations received: $n")
                }
                publishStatus()
                eventChannel?.trySend(ev)
            }
            if (msg is EoseMessage && trackedSubIds.contains(msg.subId)) {
                // Catch-up complete on this relay; we're now listening live.
                publishStatus(Sip01SyncPhase.LISTENING)
            }
        }

        override fun onDisconnected(relay: IRelayClient) {
            // quartz re-issues stored REQ s on reconnect (syncFilters); nothing to redo here.
            if (connectedRelays.remove(relay.url)) {
                Log.d(TAG, "Disconnected from index relay ${relay.url.url}")
                publishStatus()
            }
        }

        override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
            if (subscribedRelays.contains(relay.url)) {
                Log.d(TAG, "Cannot connect to index relay ${relay.url.url}: $errorMessage")
            }
            if (connectedRelays.remove(relay.url)) {
                publishStatus()
            }
        }
    }
}

private fun AtomicLong.updateMax(value: Long) {
    while (true) {
        val current = get()
        if (value <= current) return
        if (compareAndSet(current, value)) return
    }
}

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
