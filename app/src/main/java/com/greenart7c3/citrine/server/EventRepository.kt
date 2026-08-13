package com.greenart7c3.citrine.server

import androidx.sqlite.db.SimpleSQLiteQuery
import com.fasterxml.jackson.databind.JsonNode
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.IdAndCreatedAt
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.greenart7c3.citrine.server.sip01.Sip01Search
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import com.vitorpamplona.quartz.utils.TimeUtils
import java.io.StringWriter
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString

private val TAG_KEY_REGEX = Regex("^[a-zA-Z0-9]+$")
private val JACKSON_NODE_FACTORY = JacksonMapper.mapper.nodeFactory

object EventRepository {
    enum class SelectMode {
        FULL_EVENTS,
        COUNT,
        IDS_AND_CREATED_AT,
    }

    fun createQuery(
        filter: EventFilter,
        mode: SelectMode,
    ): Pair<String, List<Any>> {
        val params = mutableListOf<Any>()
        val joinClause = StringBuilder()
        val whereClause = StringBuilder()

        fun appendWhere(predicate: CharSequence) {
            if (whereClause.isEmpty()) {
                whereClause.append(" WHERE ")
            } else {
                whereClause.append(" AND ")
            }
            whereClause.append(predicate)
        }

        // --- EventEntity filters ---
        // Exclude events whose NIP-40 expiration timestamp is in the past.
        appendWhere("(EventEntity.expiresAt IS NULL OR EventEntity.expiresAt >= ?)")
        params.add(TimeUtils.now())

        filter.since?.let {
            appendWhere("EventEntity.createdAt >= ?")
            params.add(it)
        }

        filter.until?.let {
            appendWhere("EventEntity.createdAt <= ?")
            params.add(it)
        }

        if (filter.ids.isNotEmpty()) {
            val placeholders = filter.ids.joinToString(",") { "?" }
            appendWhere("EventEntity.id IN ($placeholders)")
            params.addAll(filter.ids)
        }

        if (filter.authors.isNotEmpty()) {
            val placeholders = filter.authors.joinToString(",") { "?" }
            appendWhere("EventEntity.pubkey IN ($placeholders)")
            params.addAll(filter.authors)
        }

        if (filter.searchKeywords.isNotEmpty()) {
            joinClause.append(" INNER JOIN event_fts ON event_fts.rowid = EventEntity.rowid")
            val searchParam = StringBuilder(filter.searchKeywords.joinToString(" ") { "\"$it\"" })
            filter.sip01Search.notTextTerms.forEach { searchParam.append(" NOT \"$it\"") }
            appendWhere("event_fts MATCH ?")
            params.add(searchParam.toString())
        }

        // SIP-01 web-search operators (relay profile §4) map onto the structured
        // WebIndexEntity fields extracted at ingest time. Events without an index
        // row — all non-39697 events — never match, so these operators are safe in
        // mixed-kind filters. Negated `-op:` forms emit NOT (...) predicates.
        val sip = filter.sip01Search
        if (sip.requiresWebIndexJoin) {
            appendSip01Clauses(sip, joinClause, { pred -> appendWhere(pred) }, params)
        }

        if (filter.kinds.isNotEmpty()) {
            val placeholders = filter.kinds.joinToString(",") { "?" }
            appendWhere("EventEntity.kind IN ($placeholders)")
            params.addAll(filter.kinds)
        }

        // --- Add each tag as an AND EXISTS in WHERE ---
        // Note: no JOIN against TagEntity. The EXISTS predicates below do all the tag
        // filtering; a join would multiply each event row by its tag count and force a
        // DISTINCT over full-width rows (including content) to dedupe them again, which
        // is dramatically slower.
        filter.tags.forEach { tag ->
            val safeTagKey = tag.key.takeIf { it.matches(TAG_KEY_REGEX) }
                ?: throw IllegalArgumentException("Invalid tag key: ${tag.key}")

            val existsClause = StringBuilder()
                .append("EXISTS (SELECT 1 FROM TagEntity WHERE pkEvent = EventEntity.id AND col0Name = ?")
            params.add(safeTagKey)

            if (tag.value.isNotEmpty()) {
                val valuePlaceholders = tag.value.joinToString(",") { "?" }
                existsClause.append(" AND col1Value IN (").append(valuePlaceholders).append(")")
                params.addAll(tag.value)
            }

            if (filter.kinds.isNotEmpty()) {
                val kindPlaceholders = filter.kinds.joinToString(",") { "?" }
                existsClause.append(" AND kind IN (").append(kindPlaceholders).append(")")
                params.addAll(filter.kinds)
            }

            existsClause.append(")")

            appendWhere(existsClause)
        }

        // --- Build SQL ---
        val orderBy = if (filter.searchKeywords.isNotEmpty()) {
            "EventEntity.rowid DESC"
        } else {
            "EventEntity.createdAt DESC, EventEntity.id ASC"
        }

        // `distinct:domain` / `distinct:author` collapse each host/pubkey down to
        // its newest event via SQLite's bare-column-with-MAX() rule. Filters
        // restricted to classic replaceable kinds are exempt — those already hold
        // at most one current event per author (matches the reference relay).
        val distinctColumn = when {
            filter.kinds.isNotEmpty() && filter.kinds.all { it == 0 || it == 3 || it in 10000 until 20000 } -> null
            sip.distinctDomain -> "WebIndexEntity.host"
            sip.distinctAuthor -> "EventEntity.pubkey"
            else -> null
        }

        // The FTS and WebIndexEntity joins are 1:1 (rowid = rowid, id = id) and the tag
        // filters use EXISTS, so no row in the result is ever duplicated.
        val query = StringBuilder()
        when (mode) {
            SelectMode.COUNT -> {
                query.append("SELECT COUNT(*) FROM EventEntity EventEntity")
                query.append(joinClause)
                query.append(whereClause)
            }
            SelectMode.FULL_EVENTS -> {
                if (distinctColumn != null) {
                    // MAX() + bare columns: SQLite guarantees the bare columns come
                    // from the row holding the maximum — i.e. the newest event per
                    // group. No DISTINCT: it would forbid the FTS rowid ORDER BY.
                    query.append("SELECT EventEntity.*, MAX(EventEntity.createdAt) FROM EventEntity EventEntity")
                } else {
                    query.append("SELECT EventEntity.* FROM EventEntity EventEntity")
                }
                query.append(joinClause)
                query.append(whereClause)
                if (distinctColumn != null) {
                    query.append(" GROUP BY ").append(distinctColumn)
                }
                query.append(" ORDER BY ").append(orderBy)
            }
            SelectMode.IDS_AND_CREATED_AT -> {
                query.append("SELECT EventEntity.id AS id, EventEntity.createdAt AS createdAt FROM EventEntity EventEntity")
                query.append(joinClause)
                query.append(whereClause)
                if (distinctColumn != null) {
                    query.append(" GROUP BY ").append(distinctColumn)
                }
                query.append(" ORDER BY ").append(orderBy)
            }
        }

        filter.limit?.let {
            query.append(" LIMIT ?")
            params.add(it)
        }
        return Pair(query.toString(), params)
    }

    /** Escape the LIKE wildcards in a user-supplied substring match value. */
    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /**
     * Append the SIP-01 web-search operator clauses (relay profile §4) to the
     * query under construction. Joins WebIndexEntity and emits one predicate per
     * operator occurrence; repeated keyword operators OR together, `inurl:` and
     * `title:` tokens AND together, and every `-op:` form negates.
     */
    private fun appendSip01Clauses(
        sip: Sip01Search,
        joinClause: StringBuilder,
        appendWhere: (CharSequence) -> Unit,
        params: MutableList<Any>,
    ) {
        joinClause.append(" INNER JOIN WebIndexEntity ON WebIndexEntity.id = EventEntity.id")

        // site:<host> — the document's host IS <host> or a subdomain of it
        // (Google-style site: semantics). Multiple site: tokens OR together.
        fun hostOrSubdomainClause(hosts: List<String>, negate: Boolean) {
            if (hosts.isEmpty()) return
            val clause = hosts.joinToString(" OR ") { "(WebIndexEntity.host = ? OR WebIndexEntity.host LIKE '%.' || ? ESCAPE '\\')" }
            hosts.forEach {
                params.add(it)
                params.add(it)
            }
            appendWhere(if (negate) "NOT ($clause)" else "($clause)")
        }
        hostOrSubdomainClause(sip.site, negate = false)
        hostOrSubdomainClause(sip.notSite, negate = true)

        // domain:<host> — exact host match (no subdomains).
        fun exactHostClause(hosts: List<String>, negate: Boolean) {
            if (hosts.isEmpty()) return
            val placeholders = hosts.joinToString(",") { "?" }
            params.addAll(hosts)
            appendWhere(if (negate) "WebIndexEntity.host NOT IN ($placeholders)" else "WebIndexEntity.host IN ($placeholders)")
        }
        exactHostClause(sip.domain, negate = false)
        exactHostClause(sip.notDomain, negate = true)

        // url:<url> — exact normalized-URL match (value already normalized by the parser).
        sip.url?.let {
            appendWhere("WebIndexEntity.url = ?")
            params.add(it)
        }
        sip.notUrl?.let {
            appendWhere("WebIndexEntity.url != ?")
            params.add(it)
        }

        // inurl:<text> / title:<text> — case-insensitive substring match; multiple
        // tokens AND (one predicate each).
        fun substringClause(column: String, values: List<String>, negate: Boolean) {
            values.forEach {
                appendWhere(if (negate) "$column NOT LIKE '%' || ? || '%' ESCAPE '\\'" else "$column LIKE '%' || ? || '%' ESCAPE '\\'")
                params.add(escapeLike(it))
            }
        }
        substringClause("WebIndexEntity.url", sip.inUrl, negate = false)
        substringClause("WebIndexEntity.url", sip.notInUrl, negate = true)
        substringClause("WebIndexEntity.title", sip.title, negate = false)
        substringClause("WebIndexEntity.title", sip.notTitle, negate = true)

        // topic:<t> — SIP-01 topic tags, the topical-engine slice. Evaluated
        // against the generic tag store so unvalidated (but stored) events
        // still answer topic queries.
        fun topicClause(topics: List<String>, negate: Boolean) {
            if (topics.isEmpty()) return
            val placeholders = topics.joinToString(",") { "?" }
            params.addAll(topics)
            val exists = "EXISTS (SELECT 1 FROM TagEntity WHERE pkEvent = EventEntity.id AND col0Name = 't' AND col1Value IN ($placeholders))"
            appendWhere(if (negate) "NOT $exists" else exists)
        }
        topicClause(sip.topic, negate = false)
        topicClause(sip.notTopic, negate = true)

        // Keyword column operators: type:, platform:, category:, network:,
        // country:, mime:, source:, filetype:, lang: (each OR across repeated
        // tokens, with a negated `-key:` form). Values are already normalized
        // (case) by the parser.
        fun keywordClause(column: String, values: List<String>, negate: Boolean) {
            if (values.isEmpty()) return
            val placeholders = values.joinToString(",") { "?" }
            params.addAll(values)
            appendWhere(if (negate) "$column NOT IN ($placeholders)" else "$column IN ($placeholders)")
        }
        keywordClause("WebIndexEntity.docType", sip.type, negate = false)
        keywordClause("WebIndexEntity.docType", sip.notType, negate = true)
        keywordClause("WebIndexEntity.platform", sip.platform, negate = false)
        keywordClause("WebIndexEntity.platform", sip.notPlatform, negate = true)
        keywordClause("WebIndexEntity.category", sip.category, negate = false)
        keywordClause("WebIndexEntity.category", sip.notCategory, negate = true)
        keywordClause("WebIndexEntity.network", sip.network, negate = false)
        keywordClause("WebIndexEntity.network", sip.notNetwork, negate = true)
        keywordClause("WebIndexEntity.country", sip.country, negate = false)
        keywordClause("WebIndexEntity.country", sip.notCountry, negate = true)
        keywordClause("WebIndexEntity.mime", sip.mime, negate = false)
        keywordClause("WebIndexEntity.mime", sip.notMime, negate = true)
        keywordClause("WebIndexEntity.source", sip.source, negate = false)
        keywordClause("WebIndexEntity.source", sip.notSource, negate = true)
        keywordClause("WebIndexEntity.fileExt", sip.filetype, negate = false)
        keywordClause("WebIndexEntity.fileExt", sip.notFiletype, negate = true)
        keywordClause("WebIndexEntity.language", sip.lang, negate = false)
        keywordClause("WebIndexEntity.language", sip.notLang, negate = true)

        // before:<date> / after:<date> — content-freshness range on the page's
        // claimed publication time (the SIP-01 `published` tag). Documents
        // without a declared publication date don't match. Observation time
        // remains available through the native since/until filter fields.
        sip.before?.let {
            appendWhere("WebIndexEntity.publishedAt < ?")
            params.add(it)
        }
        sip.after?.let {
            appendWhere("WebIndexEntity.publishedAt >= ?")
            params.add(it)
        }
    }

    fun query(
        database: AppDatabase,
        filter: EventFilter,
    ): List<EventWithTags> {
        val query = createQuery(filter, SelectMode.FULL_EVENTS)

        val rawSql = SimpleSQLiteQuery(query.first, query.second.toTypedArray())
        return database.eventDao().getEvents(rawSql)
    }

    fun countQuery(
        database: AppDatabase,
        filter: EventFilter,
    ): Int {
        val query = createQuery(filter, SelectMode.COUNT)

        val rawSql = SimpleSQLiteQuery(query.first, query.second.toTypedArray())
        return database.eventDao().count(rawSql)
    }

    fun idsAndCreatedAt(
        database: AppDatabase,
        filter: EventFilter,
    ): List<IdAndCreatedAt> {
        val query = createQuery(filter, SelectMode.IDS_AND_CREATED_AT)

        val rawSql = SimpleSQLiteQuery(query.first, query.second.toTypedArray())
        return database.eventDao().getIdsAndCreatedAt(rawSql)
    }

    suspend fun subscribe(
        subscription: Subscription,
        filter: EventFilter,
    ) {
        // NIP-29 private groups: events the connection may not read are filtered per
        // event, because a filter (e.g. {"kinds":[9]}) can match group content without
        // ever naming the group id. The per-event check works on the raw rows, so the
        // no-quartz-Event fast path below is preserved, and it only runs at all while a
        // private/hidden managed group exists.
        val applyNip29Gate = GroupManager.hasPrivateGroups()

        if (subscription.count) {
            val count = if (applyNip29Gate) {
                query(subscription.appDatabase, filter).count {
                    GroupManager.canRead(it, subscription.connection)
                }
            } else {
                countQuery(subscription.appDatabase, filter)
            }
            subscription.connection.send(
                "[\"COUNT\",${subscription.escapedId},{\"count\":$count}]",
            )
            return
        }

        val events = query(subscription.appDatabase, filter)
        var sent = 0
        for (dbEvent in events) {
            if (applyNip29Gate && !GroupManager.canRead(dbEvent, subscription.connection)) {
                continue
            }
            subscription.connection.send("[\"EVENT\",${subscription.escapedId},${dbEvent.toEventJson()}]")
            sent++
        }
        if (sent > 0 && Log.isLoggable(Citrine.TAG, Log.DEBUG)) {
            Log.d(Citrine.TAG, "sent $sent events for subscription ${subscription.id} filter $filter")
        }
    }

    /**
     * Serializes a stored event straight from its database row, skipping the construction
     * of a kind-specific quartz [Event] and an intermediate Jackson tree. Produces the same
     * JSON as `toEvent().toJsonObject()`: tags are flattened the same way as
     * [com.greenart7c3.citrine.database.toTags] (non-null col0..col3, then col4Plus).
     */
    private fun EventWithTags.toEventJson(): String {
        val writer = StringWriter(256 + event.content.length)
        JacksonMapper.mapper.factory.createGenerator(writer).use { gen ->
            gen.writeStartObject()
            gen.writeStringField("id", event.id)
            gen.writeStringField("pubkey", event.pubkey)
            gen.writeNumberField("created_at", event.createdAt)
            gen.writeNumberField("kind", event.kind)
            gen.writeArrayFieldStart("tags")
            for (tag in tags) {
                gen.writeStartArray()
                tag.col0Name?.let { gen.writeString(it) }
                tag.col1Value?.let { gen.writeString(it) }
                tag.col2Differentiator?.let { gen.writeString(it) }
                tag.col3Amount?.let { gen.writeString(it) }
                tag.col4Plus.forEach { gen.writeString(it) }
                gen.writeEndArray()
            }
            gen.writeEndArray()
            gen.writeStringField("content", event.content)
            gen.writeStringField("sig", event.sig)
            gen.writeEndObject()
        }
        return writer.toString()
    }
}

fun Event.toJsonObject(): JsonNode = JACKSON_NODE_FACTORY.objectNode().apply {
    put("id", id)
    put("pubkey", pubKey)
    put("created_at", createdAt)
    put("kind", kind)
    replace(
        "tags",
        JACKSON_NODE_FACTORY.arrayNode(tags.size).apply {
            tags.forEach { tag ->
                add(
                    JACKSON_NODE_FACTORY.arrayNode(tag.size).apply { tag.forEach { add(it) } },
                )
            }
        },
    )
    put("content", content)
    put("sig", sig)
}
