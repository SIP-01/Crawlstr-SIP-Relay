package com.greenart7c3.citrine.server.sip01

import com.fasterxml.jackson.databind.JsonNode
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * SIP-01 Web Index Observation events (kind 39697) — the decentralized
 * index record of the Searchstr ecosystem.
 *
 * SIP-01 (Search Index Protocol) is the shared contract between crawlers
 * (Crawlstr, indexstr), index relays (UNCAGED Index Relay, this app), and
 * search engines (0xSearchstr, 0xPresearchstr, UNCAGED Engine): crawlers
 * publish signed observations of web pages, relays validate and index them,
 * engines rank and filter them however they choose.
 *
 * Canonical specification: https://github.com/NostrDanish/SIP-01
 * (`public/spec/SIP-01.md`). Relay profile:
 * https://github.com/NostrDanish/UNCAGED-Index-Relay (`docs/SIP-01.md`).
 *
 * Byte-compatibility is rule zero: [normalizeIndexUrl] (spec §7),
 * [webDocumentDTag] (§3) and [webDocumentContentHash] (§8) are covered by
 * the spec's §13 test vectors in `WebDocumentTest` — keep them green; a
 * single character of drift breaks deduplication against every other
 * implementation.
 *
 * One shared decentralized index. Many independent indexers. No single owner.
 */
object WebDocument {
    /** Event kind for SIP-01 Web Index Observations (addressable range). */
    const val WEB_DOCUMENT_KIND = 39697

    /** Current SIP-01 schema version (the `v` tag). */
    const val WEB_DOCUMENT_SCHEMA_VERSION = "1"

    /** Prefix of the URL-identity `d` tag (SIP-01 §3). */
    const val WEB_DOCUMENT_D_PREFIX = "widx:"

    /** Maximum length of the `u` tag value. */
    const val WEB_DOC_URL_MAX_LENGTH = 2048

    /** Title length cap (SIP-01 §5): 1–300 chars after trim. */
    const val WEB_DOC_TITLE_MAX_LENGTH = 300

    /** Description length cap (SIP-01 §6). */
    const val WEB_DOC_DESCRIPTION_MAX_LENGTH = 1000

    /** Maximum number of topic (`t`) tags (SIP-01 §6). */
    const val WEB_DOC_MAX_TOPICS = 8

    /** Generous cap for the NIP-31 `alt` description. */
    private const val ALT_MAX_LENGTH = 1000

    /** Cap for the informational `source` tag. */
    private const val SOURCE_MAX_LENGTH = 100

    /**
     * Tracking parameters stripped during SIP-01 §7 URL normalization. All other
     * query parameters are preserved (many are semantically required).
     */
    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "dclid", "mc_cid", "mc_eid", "igshid", "ref_src",
        "spm", "si",
    )

    /** ISO 639-1 two-letter language code (the `l` tag). */
    private val LANG_RE = Regex("^[a-z]{2}$")

    /** Lowercase topic tag value (SIP-01 §6: `t` tags are lowercase topics). */
    private val TOPIC_RE = Regex("^[a-z0-9][a-z0-9-]{0,99}$")

    /** Extension tag values: `page`, `repository`, `github`, `clearnet`, ... */
    private val EXTENSION_VALUE_RE = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,49}$")

    /** MIME type with optional parameters: `text/html; charset=utf-8`. */
    private val MIME_RE = Regex(
        "^[a-zA-Z0-9][a-zA-Z0-9!#\$&^_.+-]{0,126}/[a-zA-Z0-9][a-zA-Z0-9!#\$&^_.+-]{0,126}(;\\s*[^\\s;=]+=[^\\s;]+)*$",
    )

    /** Dotted-quad IPv4 address (domain hierarchy makes no sense for IPs). */
    private val IPV4_RE = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    private val HEX_RE = Regex("^[0-9a-f]{64}$")

    private val PUBLISHED_RE = Regex("^\\d{1,16}$")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * WHATWG application/x-www-form-urlencoded percent-encode set: alphanumerics
     * and `*`, `-`, `.`, `_` pass through, space becomes `+`, every other byte
     * is percent-encoded with uppercase hex. Matches `URLSearchParams.toString()`.
     */
    private fun formUrlEncode(value: String): String {
        val sb = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            when {
                c == 0x20 -> sb.append('+')
                c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A ||
                    c == 0x2A || c == 0x2D || c == 0x2E || c == 0x5F -> sb.append(c.toChar())
                else -> sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private fun formUrlDecode(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '+') {
                bytes.add(0x20)
                i++
            } else if (c == '%' && i + 2 < value.length) {
                val parsed = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (parsed != null) {
                    bytes.add(parsed.toByte())
                    i += 3
                } else {
                    bytes.addAll(c.toString().toByteArray(Charsets.UTF_8).toList())
                    i++
                }
            } else {
                bytes.addAll(c.toString().toByteArray(Charsets.UTF_8).toList())
                i++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Normalize a URL per SIP-01 §7. Implementations MUST produce byte-identical
     * results across the ecosystem or `d`-tag deduplication breaks:
     *
     * 1. Parse; reject anything not `http://` or `https://`.
     * 2. Lowercase scheme and host; strip a leading `www.` from the host.
     * 3. Remove default ports (`:80` http, `:443` https).
     * 4. Remove the fragment entirely.
     * 5. Remove known tracking parameters (case-insensitively); all other query
     *    parameters are preserved.
     * 6. Sort remaining query parameters alphabetically by key (stable for
     *    duplicate keys).
     * 7. Remove a trailing `/` from the path (except the bare root `/`).
     * 8. Re-encode via `URL.toString()` (WHATWG semantics).
     *
     * Returns null when the input is not a valid http(s) URL.
     */
    fun normalizeIndexUrl(raw: String): String? {
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null

        var host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host.isEmpty()) return null

        // 2. Strip a leading www.
        if (host.startsWith("www.")) {
            host = host.substring(4)
        }

        // 3. Default ports (the WHATWG parser drops them too).
        val defaultPort = if (scheme == "http") 80 else 443
        val port = if (uri.port == -1 || uri.port == defaultPort) -1 else uri.port

        // 5–6. Drop tracking parameters, stable-sort the rest by (decoded) key.
        // Keys/values are re-encoded through the urlencoded serializer, which
        // also normalizes away a bare trailing `?`.
        val rawQuery = uri.rawQuery
        val sortedQuery = if (rawQuery.isNullOrEmpty()) {
            ""
        } else {
            rawQuery.split('&')
                .filter { it.isNotEmpty() }
                .map { pair ->
                    val eq = pair.indexOf('=')
                    val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
                    val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
                    formUrlDecode(rawKey) to formUrlDecode(rawValue)
                }
                .filter { (key, _) -> key.lowercase(Locale.ROOT) !in TRACKING_PARAMS }
                .sortedWith { a, b -> a.first.compareTo(b.first) }
                .joinToString("&") { (key, value) -> "${formUrlEncode(key)}=${formUrlEncode(value)}" }
        }

        // 7. Trailing slash (keep the bare root "/"). WHATWG serializes an
        // empty path for special schemes as "/", so an empty path becomes "/".
        var path = uri.rawPath ?: ""
        if (path.isEmpty()) path = "/"
        if (path.length > 1 && path.endsWith("/")) {
            path = path.dropLast(1)
        }

        val userInfo = uri.rawUserInfo?.takeIf { it.isNotEmpty() }?.let { "$it@" } ?: ""
        val hostPart = if (host.contains(':')) "[$host]" else host
        val portPart = if (port >= 0) ":$port" else ""
        val queryPart = if (sortedQuery.isNotEmpty()) "?$sortedQuery" else ""

        // 4. No fragment. 8. Reassembled per WHATWG URL serialization.
        return "$scheme://$userInfo$hostPart$portPart$path$queryPart"
    }

    /**
     * The SIP-01 §3 `d` tag value for a normalized URL:
     * `"widx:" + sha256(url)[0:32]` (lowercase hex). Deterministic across all
     * indexers — that is what makes independent-observation counting work.
     */
    fun webDocumentDTag(normalizedUrl: String): String = WEB_DOCUMENT_D_PREFIX + sha256Hex(normalizedUrl).substring(0, 32)

    /**
     * The SIP-01 §8 content identity: lowercase hex SHA-256 of
     * `title + "\n" + description` (empty string when description is absent).
     */
    fun webDocumentContentHash(title: String, description: String?): String = sha256Hex("$title\n${description ?: ""}")

    /** All values of the tags with the given name that carry a value. */
    private fun tagValues(event: Event, name: String): List<String> = event.tags.filter { it.size > 1 && it[0] == name && it[1].isNotEmpty() }.map { it[1] }

    /** The single value of a tag expected at most once, or null. */
    private fun tagValue(event: Event, name: String): String? = tagValues(event, name).firstOrNull()

    /** Parsed content JSON of a web index observation. */
    data class WebDocumentContent(
        val title: String,
        val description: String?,
        val image: String?,
    )

    /**
     * Parse the content JSON of a web document event. Returns null when the
     * content is not a JSON object or the required `title` is not a string.
     */
    fun parseWebDocumentContent(content: String): WebDocumentContent? {
        val parsed: JsonNode = try {
            JacksonMapper.mapper.readTree(content)
        } catch (e: Exception) {
            return null
        }
        if (!parsed.isObject) return null
        val title = parsed.get("title")?.takeIf { it.isTextual }?.asText() ?: return null
        val description = parsed.get("description")?.takeIf { it.isTextual }?.asText()
        val image = parsed.get("image")?.takeIf { it.isTextual }?.asText()
        return WebDocumentContent(title, description, image)
    }

    /**
     * Validate a kind 39697 web index observation for ingestion.
     *
     * Returns a human-readable reason string (without the `invalid:` prefix) when
     * the event is malformed, or null when it is acceptable. Called by the relay
     * after signature verification; invalid observations are rejected and never
     * reach the index (SIP-01 §12.4 relay-side validation). Unknown tags are
     * ignored (§9.1.3 forwards compatibility); unknown schema versions are
     * rejected (§10).
     */
    fun validateWebDocument(event: Event): String? {
        if (event.kind != WEB_DOCUMENT_KIND) return null

        // --- Required tags: exactly one d, u, v, alt each.

        val dTags = event.tags.filter { it.isNotEmpty() && it[0] == "d" }
        if (dTags.isEmpty() || dTags[0].getOrNull(1).isNullOrEmpty()) return "web document missing d tag"
        if (dTags.size > 1) return "web document has multiple d tags"
        val dTag = dTags[0][1]

        val uTags = event.tags.filter { it.isNotEmpty() && it[0] == "u" }
        if (uTags.isEmpty() || uTags[0].getOrNull(1).isNullOrEmpty()) return "web document missing u tag"
        if (uTags.size > 1) return "web document has multiple u tags"
        val uTag = uTags[0][1]

        val vTags = event.tags.filter { it.isNotEmpty() && it[0] == "v" }
        if (vTags.isEmpty() || vTags[0].getOrNull(1).isNullOrEmpty()) return "web document missing v tag"
        if (vTags.size > 1) return "web document has multiple v tags"
        if (vTags[0][1] != WEB_DOCUMENT_SCHEMA_VERSION) {
            return "unsupported web document schema version \"${vTags[0][1]}\""
        }

        val altTags = event.tags.filter { it.isNotEmpty() && it[0] == "alt" }
        if (altTags.isEmpty() || altTags[0].getOrNull(1).isNullOrBlank()) {
            return "web document missing alt tag"
        }
        if (altTags.size > 1) return "web document has multiple alt tags"
        if (altTags[0][1].length > ALT_MAX_LENGTH) {
            return "alt tag exceeds $ALT_MAX_LENGTH characters"
        }

        // --- URL allowlist + d ↔ normalized u consistency (SIP-01 §7, §11).

        if (uTag.length > WEB_DOC_URL_MAX_LENGTH) {
            return "u tag exceeds $WEB_DOC_URL_MAX_LENGTH characters"
        }
        val normalized = normalizeIndexUrl(uTag)
            ?: return "u tag is not a valid http(s) URL"
        if (dTag != webDocumentDTag(normalized)) {
            return "d tag does not match the normalized u tag (widx: + sha256(u)[0:32])"
        }

        // --- Content JSON: title is required (1–300 trimmed), description and
        // --- image optional with caps.

        val content = parseWebDocumentContent(event.content)
            ?: return "web document content is not valid JSON with a title"

        val trimmedTitle = content.title.trim()
        if (trimmedTitle.isEmpty() || trimmedTitle.length > WEB_DOC_TITLE_MAX_LENGTH) {
            return "title must be 1-$WEB_DOC_TITLE_MAX_LENGTH characters"
        }
        if (content.description != null && content.description.length > WEB_DOC_DESCRIPTION_MAX_LENGTH) {
            return "description exceeds $WEB_DOC_DESCRIPTION_MAX_LENGTH characters"
        }
        if (content.image != null) {
            val imageUrl = try {
                URI(content.image)
            } catch (e: Exception) {
                null
            }
            if (imageUrl?.scheme?.lowercase(Locale.ROOT) != "https") {
                return "image must be an https URL"
            }
        }

        // --- Optional tags, validated when present.

        val topics = event.tags.filter { it.isNotEmpty() && it[0] == "t" }
        if (topics.size > WEB_DOC_MAX_TOPICS) {
            return "web document has more than $WEB_DOC_MAX_TOPICS topic tags"
        }
        for (topic in topics) {
            if (topic.getOrNull(1).isNullOrEmpty() || !TOPIC_RE.matches(topic[1])) {
                return "topic (t) tags must be lowercase alphanumeric words"
            }
        }

        val lang = tagValue(event, "l")
        if (lang != null && !LANG_RE.matches(lang)) {
            return "l tag is not a valid ISO 639-1 language code"
        }

        // The x tag is the content-agreement signal; an incorrect hash is worse
        // than none, so it is verified against the observed metadata (SIP-01 §8).
        val x = tagValue(event, "x")
        if (x != null) {
            if (!HEX_RE.matches(x)) {
                return "x tag must be a lowercase hex sha256 digest"
            }
            if (x != webDocumentContentHash(content.title, content.description)) {
                return "x tag does not match sha256(title + \\n + description)"
            }
        }

        val published = tagValue(event, "published")
        if (published != null && !PUBLISHED_RE.matches(published)) {
            return "published tag must be a unix timestamp in seconds"
        }

        val source = tagValue(event, "source")
        if (source != null && source.length > SOURCE_MAX_LENGTH) {
            return "source tag exceeds $SOURCE_MAX_LENGTH characters"
        }

        // Optional extension tags (SIP-01 §9): free-form but keyword-shaped.
        for (name in listOf("type", "platform", "category", "network")) {
            val value = tagValue(event, name)
            if (value != null && !EXTENSION_VALUE_RE.matches(value)) {
                return "$name tag is not a valid keyword"
            }
        }

        val country = tagValue(event, "country")
        if (country != null && !Regex("^[a-zA-Z]{2}$").matches(country)) {
            return "country tag must be an ISO 3166-1 alpha-2 code"
        }

        val mime = tagValue(event, "mime")
        if (mime != null && !MIME_RE.matches(mime)) {
            return "mime tag is not a valid MIME type"
        }

        return null
    }

    /**
     * Structured fields indexed for a web document event (relay profile §2).
     * Fields sourced from optional tags are set only when present and valid, so
     * the index stays sparse. The Nostr event remains the source of truth; this
     * row is the local acceleration layer and is never published back to Nostr.
     */
    data class WebDocumentFields(
        /** Normalized canonical URL (SIP-01 §7). */
        val url: String,
        /** Full hostname, lowercased, e.g. `docs.github.com`. */
        val urlHost: String,
        /** Lowercased file extension from the URL path, e.g. `pdf`. */
        val fileExt: String?,
        /** Document title (content JSON). */
        val title: String,
        /** Document description (content JSON), when present. */
        val description: String?,
        /** Content identity (the `x` tag): sha256(title + \n + description). */
        val contentHash: String?,
        /** The page's claimed publication time (the `published` tag). */
        val publishedAt: Long?,
        /**
         * Observation time: the event's `created_at` — the crawler's SIGNED claim.
         * A signer can backdate it; treat as crawler-asserted, not relay-attested.
         */
        val observedAt: Long,
        /** Indexer software identifier (the `source` tag), e.g. `crawlstr/1`. */
        val source: String?,
        /** ISO 639-1 language code (the `l` tag). */
        val language: String?,
        /** Logical document type (the `type` extension tag), lowercased. */
        val docType: String?,
        /** Source platform (the `platform` extension tag), e.g. `github`. */
        val platform: String?,
        /** Content category (the `category` extension tag). */
        val category: String?,
        /** Network the document lives on (the `network` extension tag), e.g. `clearnet`, `tor`. */
        val network: String?,
        /** ISO 3166-1 alpha-2 country code (the `country` extension tag), uppercased. */
        val country: String?,
        /** MIME type (the `mime` extension tag), lowercased. */
        val mime: String?,
        /** The URL-identity `d` tag (widx:...). */
        val dTag: String,
    )

    /**
     * Extract the structured index fields from a web document event.
     *
     * Returns null when the event is not a usable web document (wrong kind,
     * unusable `u` tag, or unparseable content JSON) — callers index the event
     * as a plain event in that case. On the ingest path, invalid documents are
     * rejected by [validateWebDocument] before extraction ever runs.
     */
    fun extractWebDocumentFields(event: Event): WebDocumentFields? {
        if (event.kind != WEB_DOCUMENT_KIND) return null

        val rawUrl = tagValue(event, "u") ?: return null
        val url = normalizeIndexUrl(rawUrl) ?: return null
        val content = parseWebDocumentContent(event.content) ?: return null

        val host = try {
            URI(url).host?.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        } ?: return null

        val ext = fileExtension(URI(url).rawPath ?: "")

        val x = tagValue(event, "x")?.takeIf { HEX_RE.matches(it) }

        val published = tagValue(event, "published")?.takeIf { PUBLISHED_RE.matches(it) }?.toLongOrNull()

        val source = tagValue(event, "source")?.takeIf { it.isNotEmpty() }

        val docType = tagValue(event, "type")?.takeIf { EXTENSION_VALUE_RE.matches(it) }?.lowercase(Locale.ROOT)
        val platform = tagValue(event, "platform")?.takeIf { EXTENSION_VALUE_RE.matches(it) }?.lowercase(Locale.ROOT)
        val category = tagValue(event, "category")?.takeIf { EXTENSION_VALUE_RE.matches(it) }?.lowercase(Locale.ROOT)
        val network = tagValue(event, "network")?.takeIf { EXTENSION_VALUE_RE.matches(it) }?.lowercase(Locale.ROOT)
        val country = tagValue(event, "country")?.takeIf { Regex("^[a-zA-Z]{2}$").matches(it) }?.uppercase(Locale.ROOT)
        val mime = tagValue(event, "mime")?.takeIf { MIME_RE.matches(it) }?.lowercase(Locale.ROOT)
        val language = tagValue(event, "l")?.takeIf { LANG_RE.matches(it) }

        return WebDocumentFields(
            url = url,
            urlHost = host,
            fileExt = ext,
            title = content.title,
            description = content.description,
            contentHash = x,
            publishedAt = published,
            observedAt = event.createdAt,
            source = source,
            language = language,
            docType = docType,
            platform = platform,
            category = category,
            network = network,
            country = country,
            mime = mime,
            dTag = tagValue(event, "d") ?: "",
        )
    }

    /**
     * Build the domain hierarchy for a host: the host itself plus every dotted
     * parent suffix. `docs.github.com` → `["docs.github.com", "github.com"]`;
     * `github.com` → `["github.com"]`. Bare TLDs (`com`) and IP address
     * "suffixes" are excluded; single-label hosts (`localhost`, `[::1]`) yield
     * just the host.
     */
    fun domainHierarchy(host: String): List<String> {
        if (host.isEmpty() || IPV4_RE.matches(host) || host.contains(':')) return listOf(host)
        val parts = host.split(".")
        val out = mutableListOf(host)
        for (i in 1 until parts.size - 1) {
            out.add(parts.subList(i, parts.size).joinToString("."))
        }
        return out
    }

    /**
     * Lowercased file extension of a URL path's last segment, when it looks like
     * a real extension (`/a/page.html` → `html`, `/a/file.tar.gz` → `gz`).
     * Returns null for dotfiles, trailing dots, and over-long "extensions".
     */
    fun fileExtension(pathname: String): String? {
        val lastSegment = pathname.substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        if (dot <= 0) return null
        val ext = lastSegment.substring(dot + 1).lowercase(Locale.ROOT)
        return if (Regex("^[a-z0-9]{1,10}$").matches(ext)) ext else null
    }

    /**
     * Normalize a `site:`/`domain:` operator value to a bare host, forgiving
     * common input forms (`https://github.com/x` → `github.com`,
     * `GitHub.com.` → `github.com`, `www.github.com` → `github.com` — matching
     * SIP-01 §7 host normalization). Returns null when unusable; an unusable
     * operator value adds no clause.
     */
    fun searchHostValue(value: String): String? {
        var v = value.trim().lowercase(Locale.ROOT)
        if (v.isEmpty()) return null
        if (v.contains("://")) {
            val normalized = normalizeIndexUrl(v) ?: return null
            v = try {
                URI(normalized).host?.lowercase(Locale.ROOT) ?: return null
            } catch (e: Exception) {
                return null
            }
        }
        if (v.startsWith("www.")) v = v.substring(4)
        if (v.endsWith(".")) v = v.dropLast(1)
        if (v.contains("/")) v = v.substringBefore('/') // e.g. "github.com/torvalds"
        if (v.length > 253 || !Regex("^[a-z0-9.\\-:\\[\\]]+$").matches(v)) return null
        return v
    }
}
