package com.greenart7c3.citrine.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.server.sip01.WebDocument
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.Sip01IndexSync
import com.greenart7c3.citrine.service.Sip01SyncPhase
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import com.greenart7c3.citrine.ui.components.SwitchSettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Sip01SettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val invalidRelayMsg = stringResource(R.string.relay_aggregator_invalid_relay)

    Surface(modifier) {
        var sip01Enabled by remember { mutableStateOf(Settings.sip01Enabled) }
        var sip01ValidationEnabled by remember { mutableStateOf(Settings.sip01ValidationEnabled) }
        var sip01SyncEnabled by remember { mutableStateOf(Settings.sip01SyncEnabled) }
        var sip01SyncWifiOnly by remember { mutableStateOf(Settings.sip01SyncWifiOnly) }
        var sip01SyncPauseOnLimited by remember { mutableStateOf(Settings.sip01SyncPauseOnLimitedNetwork) }
        var indexRelays by remember { mutableStateOf(Settings.sip01IndexRelays) }
        var indexRelayInput by remember { mutableStateOf(TextFieldValue("")) }
        var scopeText by remember { mutableStateOf(TextFieldValue(Settings.sip01Scope)) }
        var domainsText by remember { mutableStateOf(TextFieldValue(Settings.sip01Domains.joinToString(","))) }
        var languagesText by remember { mutableStateOf(TextFieldValue(Settings.sip01Languages.joinToString(","))) }
        var docTypesText by remember { mutableStateOf(TextFieldValue(Settings.sip01DocTypes.joinToString(","))) }

        val syncStatus by Sip01IndexSync.status.collectAsState()

        val applyChanges = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.sip01Enabled = sip01Enabled
                Settings.sip01ValidationEnabled = sip01ValidationEnabled
                Settings.sip01SyncEnabled = sip01SyncEnabled
                Settings.sip01SyncWifiOnly = sip01SyncWifiOnly
                Settings.sip01SyncPauseOnLimitedNetwork = sip01SyncPauseOnLimited
                Settings.sip01IndexRelays = indexRelays
                Settings.sip01Scope = scopeText.text.trim().ifBlank { "global" }
                Settings.sip01Domains = domainsText.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                Settings.sip01Languages = languagesText.text.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                Settings.sip01DocTypes = docTypesText.text.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)

                Sip01IndexSync.onConfigChanged(AppDatabase.getDatabase(context))
                onApplyChanges()
                delay(1500)
                isLoading = false
            }
            Unit
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Text(
                        text = stringResource(R.string.sip01_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.sip01_enabled),
                        description = stringResource(R.string.sip01_enabled_description, WebDocument.WEB_DOCUMENT_KIND),
                        checked = sip01Enabled,
                        onCheckedChange = { sip01Enabled = it },
                    )
                }
                if (sip01Enabled) {
                    item {
                        SwitchSettingRow(
                            title = stringResource(R.string.sip01_validation),
                            description = stringResource(R.string.sip01_validation_description),
                            checked = sip01ValidationEnabled,
                            onCheckedChange = { sip01ValidationEnabled = it },
                        )
                    }
                    item {
                        SwitchSettingRow(
                            title = stringResource(R.string.sip01_sync),
                            description = stringResource(R.string.sip01_sync_description),
                            checked = sip01SyncEnabled,
                            onCheckedChange = { sip01SyncEnabled = it },
                        )
                    }
                    if (sip01SyncEnabled) {
                        item {
                            val phaseText = stringResource(
                                when (syncStatus.phase) {
                                    Sip01SyncPhase.IDLE -> R.string.sip01_sync_phase_idle
                                    Sip01SyncPhase.SYNCING -> R.string.sip01_sync_phase_syncing
                                    Sip01SyncPhase.LISTENING -> R.string.sip01_sync_phase_listening
                                    Sip01SyncPhase.PAUSED -> R.string.sip01_sync_phase_paused
                                },
                            )
                            Text(
                                text = stringResource(
                                    R.string.sip01_sync_status,
                                    phaseText,
                                    syncStatus.relaysConnected,
                                    syncStatus.relaysConfigured,
                                    syncStatus.eventsReceived,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        item {
                            SwitchSettingRow(
                                title = stringResource(R.string.relay_aggregator_wifi_only),
                                description = stringResource(R.string.relay_aggregator_wifi_only_description),
                                checked = sip01SyncWifiOnly,
                                onCheckedChange = { sip01SyncWifiOnly = it },
                            )
                        }
                        item {
                            SwitchSettingRow(
                                title = stringResource(R.string.relay_aggregator_pause_on_limited_network),
                                description = stringResource(R.string.relay_aggregator_pause_on_limited_network_description),
                                checked = sip01SyncPauseOnLimited,
                                onCheckedChange = { sip01SyncPauseOnLimited = it },
                            )
                        }
                        item {
                            Text(
                                text = stringResource(R.string.sip01_index_relays),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        item {
                            Text(
                                text = stringResource(R.string.sip01_index_relays_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        item {
                            PubkeyInputRow(
                                value = indexRelayInput,
                                onValueChange = { indexRelayInput = it },
                                onPaste = {
                                    scope.launch {
                                        val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                        indexRelayInput = TextFieldValue(text)
                                    }
                                },
                                onAdd = {
                                    val normalized = normalizeRelayInput(indexRelayInput.text)
                                    if (normalized == null) {
                                        Toast.makeText(context, invalidRelayMsg, Toast.LENGTH_SHORT).show()
                                        return@PubkeyInputRow
                                    }
                                    indexRelays = indexRelays + normalized
                                    indexRelayInput = TextFieldValue("")
                                },
                            )
                        }
                        item {
                            TextButton(
                                onClick = { indexRelays = Settings.DEFAULT_SIP01_INDEX_RELAYS },
                            ) {
                                Text(stringResource(R.string.relay_aggregator_reset_defaults))
                            }
                        }
                        if (indexRelays.isEmpty()) {
                            item {
                                EmptyListHint(stringResource(R.string.sip01_index_relays_hint))
                            }
                        }
                        items(indexRelays.toList()) { relay ->
                            PubkeyListItem(
                                text = relay,
                                onDelete = { indexRelays = indexRelays - relay },
                            )
                        }
                    }

                    item {
                        SectionHeader(stringResource(R.string.sip01_scope_header))
                    }
                    item {
                        Text(
                            text = stringResource(R.string.sip01_scope_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    item {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            value = scopeText,
                            label = { Text(stringResource(R.string.sip01_scope)) },
                            onValueChange = { scopeText = it },
                            singleLine = true,
                        )
                    }
                    item {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            value = domainsText,
                            label = { Text(stringResource(R.string.sip01_domains)) },
                            onValueChange = { domainsText = it },
                            singleLine = true,
                        )
                    }
                    item {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            value = languagesText,
                            label = { Text(stringResource(R.string.sip01_languages)) },
                            onValueChange = { languagesText = it },
                            singleLine = true,
                        )
                    }
                    item {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            value = docTypesText,
                            label = { Text(stringResource(R.string.sip01_doc_types)) },
                            onValueChange = { docTypesText = it },
                            singleLine = true,
                        )
                    }
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
