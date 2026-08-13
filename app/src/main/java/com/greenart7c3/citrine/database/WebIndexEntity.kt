package com.greenart7c3.citrine.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.greenart7c3.citrine.server.sip01.WebDocument
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.flow.Flow

/**
 * SIP-01 structured index row for a kind 39697 web index observation.
 *
 * The Nostr event (EventEntity/TagEntity) remains the source of truth — this
 * table is the local acceleration layer that the relay profile
 * (UNCAGED-Index-Relay `docs/SIP-01.md` §2) calls "dedicated fields": the
 * NIP-50 web-search operators (`site:`, `domain:`, `url:`, `inurl:`,
 * `title:`, `type:`, `platform:`, `category:`, `network:`, `country:`,
 * `mime:`, `filetype:`, `source:`, `lang:`, `before:`, `after:`) map onto
 * these columns instead of scanning tag soup. Rows are never published back
 * to Nostr.
 *
 * The FK cascade on [id] keeps the index in lockstep with the event store:
 * kind-5 deletions, replaceable-slot overwrites, retention sweeps, and manual
 * deletes all remove the index row with the event.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            childColumns = ["id"],
            parentColumns = ["id"],
            onDelete = CASCADE,
        ),
    ],
    indices = [
        Index(value = ["dTag"], name = "idx_web_index_d_tag"),
        Index(value = ["host"], name = "idx_web_index_host"),
        Index(value = ["observedAt"], name = "idx_web_index_observed_at"),
        Index(value = ["publishedAt"], name = "idx_web_index_published_at"),
        Index(value = ["language"], name = "idx_web_index_language"),
        Index(value = ["docType"], name = "idx_web_index_doc_type"),
        Index(value = ["platform"], name = "idx_web_index_platform"),
    ],
)
data class WebIndexEntity(
    /** Event id — one live row per observation event. */
    @PrimaryKey val id: String,
    /** URL identity (SIP-01 §3): `widx:` + sha256(normalized_url)[0:32]. */
    val dTag: String,
    /** Normalized canonical URL (SIP-01 §7). */
    val url: String,
    /** Full hostname, lowercased — powers `site:`/`domain:`. */
    val host: String,
    /** Lowercased file extension of the URL path — powers `filetype:`. */
    val fileExt: String?,
    /** Document title (content JSON) — powers `title:`. */
    val title: String,
    /** Document description (content JSON). */
    val description: String?,
    /** ISO 639-1 language code (the `l` tag) — powers `lang:`. */
    val language: String?,
    /** Content identity (the `x` tag): sha256(title + \n + description). */
    val contentHash: String?,
    /** The page's claimed publication time (the `published` tag) — powers `before:`/`after:`. */
    val publishedAt: Long?,
    /** Observation time: the event's `created_at` (crawler's signed claim). */
    val observedAt: Long,
    /**
     * Relay-local wall clock at index time — the only timestamp the relay
     * itself attests (cannot be backdated by a publisher). Never on the wire.
     */
    val firstSeenAt: Long,
    /** Indexer software identifier (the `source` tag), e.g. `crawlstr/1`. */
    val source: String?,
    /** Logical document type (the `type` extension tag), lowercased. */
    val docType: String?,
    /** Source platform (the `platform` extension tag), lowercased. */
    val platform: String?,
    /** Content category (the `category` extension tag), lowercased. */
    val category: String?,
    /** Network the document lives on (the `network` extension tag), lowercased. */
    val network: String?,
    /** ISO 3166-1 alpha-2 country code (the `country` extension tag), uppercased. */
    val country: String?,
    /** MIME type (the `mime` extension tag), lowercased. */
    val mime: String?,
)

fun Event.toWebIndexEntity(firstSeenAt: Long): WebIndexEntity? {
    val fields = WebDocument.extractWebDocumentFields(this) ?: return null
    return WebIndexEntity(
        id = id,
        dTag = fields.dTag,
        url = fields.url,
        host = fields.urlHost,
        fileExt = fields.fileExt,
        title = fields.title,
        description = fields.description,
        language = fields.language,
        contentHash = fields.contentHash,
        publishedAt = fields.publishedAt,
        observedAt = fields.observedAt,
        firstSeenAt = firstSeenAt,
        source = fields.source,
        docType = fields.docType,
        platform = fields.platform,
        category = fields.category,
        network = fields.network,
        country = fields.country,
        mime = fields.mime,
    )
}

@Dao
interface WebIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WebIndexEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<WebIndexEntity>)

    @Query("DELETE FROM WebIndexEntity WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM WebIndexEntity")
    fun countFlow(): Flow<Long>

    @Query("SELECT COUNT(*) FROM WebIndexEntity")
    suspend fun count(): Long

    @Query("SELECT COUNT(DISTINCT dTag) FROM WebIndexEntity")
    suspend fun countDistinctUrls(): Long

    @Query("SELECT COUNT(DISTINCT pubkey) FROM EventEntity WHERE kind = 39697")
    suspend fun countDistinctIndexers(): Long

    /**
     * Kind 39697 events missing an index row, paged by id so the startup
     * backfill advances even when some events are unindexable (invalid
     * documents stored while validation was disabled).
     */
    @Query(
        """
        SELECT EventEntity.* FROM EventEntity
        LEFT JOIN WebIndexEntity ON WebIndexEntity.id = EventEntity.id
        WHERE EventEntity.kind = 39697 AND WebIndexEntity.id IS NULL AND EventEntity.id > :afterId
        ORDER BY EventEntity.id ASC
        LIMIT 500
        """,
    )
    @androidx.room.Transaction
    suspend fun getUnindexedWebDocuments(afterId: String): List<EventWithTags>

    @Query("SELECT id FROM WebIndexEntity WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>
}
