package com.greenart7c3.citrine.server.sip01

import com.vitorpamplona.quartz.nip01Core.core.Event
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Parser and in-memory matcher for the SIP-01 NIP-50 web-search operators
 * (relay profile §4): `site:`, `domain:`, `url:`, `inurl:`, `title:`,
 * `topic:`, `type:`, `platform:`, `category:`, `network:`, `country:`,
 * `mime:`, `filetype:`, `source:`, `lang:` (alias `language:`), `before:`,
 * `after:`, and `distinct:domain` / `distinct:author` — each filter operator
 * with a negated `-op:` form. NIP-50 sanctions `key:value` extensions and
 * requires relays to ignore unknown ones, so unrecognized keys are dropped
 * and these queries are safe to send anywhere.
 *
 * The parsed result is consumed twice:
 *  - [com.greenart7c3.citrine.server.EventRepository] translates it into SQL
 *    against the WebIndexEntity table for stored queries, and
 *  - [matches] evaluates it in Kotlin for live subscription fan-out.
 */
data class Sip01Search(
    /** Free-text terms (tokens that are not `key:value` operators). */
    val textTerms: List<String> = emptyList(),
    /** Negated free-text terms (`-spam`). */
    val notTextTerms: List<String> = emptyList(),
    val site: List<String> = emptyList(),
    val notSite: List<String> = emptyList(),
    val domain: List<String> = emptyList(),
    val notDomain: List<String> = emptyList(),
    val url: String? = null,
    val notUrl: String? = null,
    val inUrl: List<String> = emptyList(),
    val notInUrl: List<String> = emptyList(),
    val title: List<String> = emptyList(),
    val notTitle: List<String> = emptyList(),
    val topic: List<String> = emptyList(),
    val notTopic: List<String> = emptyList(),
    val type: List<String> = emptyList(),
    val notType: List<String> = emptyList(),
    val platform: List<String> = emptyList(),
    val notPlatform: List<String> = emptyList(),
    val category: List<String> = emptyList(),
    val notCategory: List<String> = emptyList(),
    val network: List<String> = emptyList(),
    val notNetwork: List<String> = emptyList(),
    val country: List<String> = emptyList(),
    val notCountry: List<String> = emptyList(),
    val mime: List<String> = emptyList(),
    val notMime: List<String> = emptyList(),
    val source: List<String> = emptyList(),
    val notSource: List<String> = emptyList(),
    val filetype: List<String> = emptyList(),
    val notFiletype: List<String> = emptyList(),
    val lang: List<String> = emptyList(),
    val notLang: List<String> = emptyList(),
    /** `before:<date>` — upper bound (exclusive) on the `published` tag. */
    val before: Long? = null,
    /** `after:<date>` — lower bound (inclusive) on the `published` tag. */
    val after: Long? = null,
    /** `distinct:domain` — collapse results to one per host. */
    val distinctDomain: Boolean = false,
    /** `distinct:author` — collapse results to one per pubkey. */
    val distinctAuthor: Boolean = false,
) {
    /** True when the search string carried anything at all (text or operators). */
    val hasSearchClauses: Boolean
        get() = textTerms.isNotEmpty() || notTextTerms.isNotEmpty() || requiresWebIndexJoin || distinctAuthor

    /**
     * True when any operator that restricts the result set is present — i.e.
     * the query must join the web index table. `distinct:author` is excluded
     * (it collapses on EventEntity.pubkey and needs no join); `distinct:domain`
     * needs the join for the host column.
     */
    val requiresWebIndexJoin: Boolean
        get() = site.isNotEmpty() || notSite.isNotEmpty() ||
            domain.isNotEmpty() || notDomain.isNotEmpty() ||
            url != null || notUrl != null ||
            inUrl.isNotEmpty() || notInUrl.isNotEmpty() ||
            title.isNotEmpty() || notTitle.isNotEmpty() ||
            topic.isNotEmpty() || notTopic.isNotEmpty() ||
            type.isNotEmpty() || notType.isNotEmpty() ||
            platform.isNotEmpty() || notPlatform.isNotEmpty() ||
            category.isNotEmpty() || notCategory.isNotEmpty() ||
            network.isNotEmpty() || notNetwork.isNotEmpty() ||
            country.isNotEmpty() || notCountry.isNotEmpty() ||
            mime.isNotEmpty() || notMime.isNotEmpty() ||
            source.isNotEmpty() || notSource.isNotEmpty() ||
            filetype.isNotEmpty() || notFiletype.isNotEmpty() ||
            lang.isNotEmpty() || notLang.isNotEmpty() ||
            before != null || after != null || distinctDomain

    /**
     * In-memory evaluation for live subscription fan-out (the predicate path
     * in EventFilter). Mirrors the SQL translation: when any operator is
     * present the event must be an extractable kind 39697 web document —
     * documents without the structured fields never match operator clauses
     * (relay profile §4: "operators are safe to use in mixed-kind filters").
     */
    fun matches(event: Event): Boolean {
        // Free text runs against the same tokens the FTS index sees: the
        // content JSON carries title + description verbatim.
        if (textTerms.isNotEmpty() || notTextTerms.isNotEmpty()) {
            val tokens = tokenizeText(event.content)
            if (!textTerms.all { it in tokens }) return false
            if (notTextTerms.any { it in tokens }) return false
        }

        if (requiresWebIndexJoin) {
            val fields = WebDocument.extractWebDocumentFields(event) ?: return false
            val topics = event.tags.filter { it.size > 1 && it[0] == "t" }.map { it[1] }

            fun hostMatches(h: String) = fields.urlHost == h || fields.urlHost.endsWith(".$h")

            if (site.isNotEmpty() && site.none { hostMatches(it) }) return false
            if (notSite.any { hostMatches(it) }) return false
            if (domain.isNotEmpty() && fields.urlHost !in domain) return false
            if (notDomain.isNotEmpty() && fields.urlHost in notDomain) return false
            if (url != null && fields.url != url) return false
            if (notUrl != null && fields.url == notUrl) return false
            if (!inUrl.all { fields.url.contains(it, ignoreCase = true) }) return false
            if (notInUrl.any { fields.url.contains(it, ignoreCase = true) }) return false
            if (!title.all { fields.title.contains(it, ignoreCase = true) }) return false
            if (notTitle.any { fields.title.contains(it, ignoreCase = true) }) return false
            if (topic.isNotEmpty() && topics.none { it in topic }) return false
            if (notTopic.isNotEmpty() && topics.any { it in notTopic }) return false
            if (type.isNotEmpty() && fields.docType !in type) return false
            if (notType.isNotEmpty() && fields.docType in notType) return false
            if (platform.isNotEmpty() && fields.platform !in platform) return false
            if (notPlatform.isNotEmpty() && fields.platform in notPlatform) return false
            if (category.isNotEmpty() && fields.category !in category) return false
            if (notCategory.isNotEmpty() && fields.category in notCategory) return false
            if (network.isNotEmpty() && fields.network !in network) return false
            if (notNetwork.isNotEmpty() && fields.network in notNetwork) return false
            if (country.isNotEmpty() && fields.country !in country) return false
            if (notCountry.isNotEmpty() && fields.country in notCountry) return false
            if (mime.isNotEmpty() && fields.mime !in mime) return false
            if (notMime.isNotEmpty() && fields.mime in notMime) return false
            if (source.isNotEmpty() && fields.source !in source) return false
            if (notSource.isNotEmpty() && fields.source in notSource) return false
            if (filetype.isNotEmpty() && fields.fileExt !in filetype) return false
            if (notFiletype.isNotEmpty() && fields.fileExt in notFiletype) return false
            if (lang.isNotEmpty() && fields.language !in lang) return false
            if (notLang.isNotEmpty() && fields.language in notLang) return false
            if (before != null && (fields.publishedAt == null || fields.publishedAt >= before)) return false
            if (after != null && (fields.publishedAt == null || fields.publishedAt < after)) return false
        }

        return true
    }

    companion object {
        /** Operator keys this parser understands (plus their `-key` negations). */
        private val KNOWN_KEYS = setOf(
            "site", "domain", "url", "inurl", "title", "topic", "type",
            "platform", "category", "network", "country", "mime", "filetype",
            "source", "lang", "language", "before", "after", "distinct",
        )

        private val TEXT_TOKENIZE_REGEX = Regex("[^a-zA-Z0-9]")

        fun tokenizeText(string: String): Set<String> = string
            .split(TEXT_TOKENIZE_REGEX)
            .filter { it.isNotEmpty() }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

        /**
         * Split a NIP-50 search string into tokens, honoring double-quoted
         * phrases: `"exact phrase"` is one text token, `title:"hello world"`
         * attaches a quoted value to its operator.
         */
        internal fun tokenize(input: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < input.length) {
                val c = input[i]
                when {
                    c == '"' -> inQuotes = !inQuotes
                    c.isWhitespace() && !inQuotes -> {
                        if (current.isNotEmpty()) {
                            tokens.add(current.toString())
                            current.clear()
                        }
                    }
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotEmpty()) tokens.add(current.toString())
            return tokens
        }

        /**
         * Parse a `before:`/`after:` operator value into unix seconds. Accepts
         * unix seconds verbatim or an ISO calendar date (`YYYY-MM-DD`, UTC
         * midnight). Returns null for anything else — an unparseable token
         * adds no clause (NIP-50 ignore-unknown rule).
         */
        fun parseSearchDateToken(value: String): Long? {
            if (Regex("^\\d{1,16}$").matches(value)) return value.toLongOrNull()
            val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(value) ?: return null
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt() - 1,
                m.groupValues[3].toInt(),
            )
            return cal.timeInMillis / 1000
        }

        /**
         * Parse the NIP-50 `search` string of a filter. Unknown `key:value`
         * tokens are ignored per NIP-50; everything else becomes a free-text
         * term (with `-` prefix marking negation), matching the tokenization
         * the FTS path applies.
         */
        fun parse(search: String?): Sip01Search {
            if (search.isNullOrBlank()) return Sip01Search()

            val textTerms = mutableListOf<String>()
            val notTextTerms = mutableListOf<String>()
            val site = mutableListOf<String>()
            val notSite = mutableListOf<String>()
            val domain = mutableListOf<String>()
            val notDomain = mutableListOf<String>()
            var url: String? = null
            var notUrl: String? = null
            val inUrl = mutableListOf<String>()
            val notInUrl = mutableListOf<String>()
            val title = mutableListOf<String>()
            val notTitle = mutableListOf<String>()
            val topic = mutableListOf<String>()
            val notTopic = mutableListOf<String>()
            val type = mutableListOf<String>()
            val notType = mutableListOf<String>()
            val platform = mutableListOf<String>()
            val notPlatform = mutableListOf<String>()
            val category = mutableListOf<String>()
            val notCategory = mutableListOf<String>()
            val network = mutableListOf<String>()
            val notNetwork = mutableListOf<String>()
            val country = mutableListOf<String>()
            val notCountry = mutableListOf<String>()
            val mime = mutableListOf<String>()
            val notMime = mutableListOf<String>()
            val source = mutableListOf<String>()
            val notSource = mutableListOf<String>()
            val filetype = mutableListOf<String>()
            val notFiletype = mutableListOf<String>()
            val lang = mutableListOf<String>()
            val notLang = mutableListOf<String>()
            var before: Long? = null
            var after: Long? = null
            var distinctDomain = false
            var distinctAuthor = false

            for (rawToken in tokenize(search)) {
                val negated = rawToken.startsWith("-") && rawToken.length > 1
                val token = if (negated) rawToken.substring(1) else rawToken

                val colon = token.indexOf(':')
                val key = if (colon > 0) token.substring(0, colon).lowercase(Locale.ROOT) else null
                val value = if (colon > 0) token.substring(colon + 1) else null

                if (key != null && value != null && key in KNOWN_KEYS) {
                    when (key) {
                        "site" -> WebDocument.searchHostValue(value)?.let { (if (negated) notSite else site).add(it) }
                        "domain" -> WebDocument.searchHostValue(value)?.let { (if (negated) notDomain else domain).add(it) }
                        "url" -> {
                            // url: values are normalized with the same SIP-01 §7 rules
                            // used at index time; only the first token applies.
                            val normalized = WebDocument.normalizeIndexUrl(value) ?: value
                            if (negated) {
                                if (notUrl == null) notUrl = normalized
                            } else {
                                if (url == null) url = normalized
                            }
                        }
                        "inurl" -> (if (negated) notInUrl else inUrl).add(value)
                        "title" -> (if (negated) notTitle else title).add(value)
                        "topic" -> (if (negated) notTopic else topic).add(value.lowercase(Locale.ROOT))
                        "type" -> (if (negated) notType else type).add(value.lowercase(Locale.ROOT))
                        "platform" -> (if (negated) notPlatform else platform).add(value.lowercase(Locale.ROOT))
                        "category" -> (if (negated) notCategory else category).add(value.lowercase(Locale.ROOT))
                        "network" -> (if (negated) notNetwork else network).add(value.lowercase(Locale.ROOT))
                        "country" -> (if (negated) notCountry else country).add(value.uppercase(Locale.ROOT))
                        "mime" -> (if (negated) notMime else mime).add(value.lowercase(Locale.ROOT))
                        "filetype" -> (if (negated) notFiletype else filetype).add(value.removePrefix(".").lowercase(Locale.ROOT))
                        "source" -> (if (negated) notSource else source).add(value)
                        "lang", "language" -> (if (negated) notLang else lang).add(value.lowercase(Locale.ROOT))
                        "before" -> if (!negated && before == null) {
                            before = parseSearchDateToken(value)
                        }
                        "after" -> if (!negated && after == null) {
                            after = parseSearchDateToken(value)
                        }
                        "distinct" -> if (!negated) {
                            if (value == "domain") distinctDomain = true
                            if (value == "author") distinctAuthor = true
                        }
                    }
                } else {
                    // Free-text term: quoted phrases arrive as one token with
                    // whitespace intact — split them back into words so each is
                    // a separate FTS term (the pre-SIP-01 tokenization behavior).
                    // Tokens with an unknown `key:value` operator shape are
                    // ignored per NIP-50.
                    val isUnknownOperator = key != null && key.matches(Regex("^[a-z][a-z0-9]*$")) && value != null && value.isNotEmpty()
                    if (!isUnknownOperator) {
                        token.split(Regex("\\s+")).forEach { part ->
                            val cleaned = part.lowercase(Locale.ROOT).replace(TEXT_TOKENIZE_REGEX, "")
                            if (cleaned.isNotEmpty()) {
                                (if (negated) notTextTerms else textTerms).add(cleaned)
                            }
                        }
                    }
                }
            }

            return Sip01Search(
                textTerms = textTerms.distinct(),
                notTextTerms = notTextTerms.distinct(),
                site = site, notSite = notSite,
                domain = domain, notDomain = notDomain,
                url = url, notUrl = notUrl,
                inUrl = inUrl, notInUrl = notInUrl,
                title = title, notTitle = notTitle,
                topic = topic, notTopic = notTopic,
                type = type, notType = notType,
                platform = platform, notPlatform = notPlatform,
                category = category, notCategory = notCategory,
                network = network, notNetwork = notNetwork,
                country = country, notCountry = notCountry,
                mime = mime, notMime = notMime,
                source = source, notSource = notSource,
                filetype = filetype, notFiletype = notFiletype,
                lang = lang, notLang = notLang,
                before = before, after = after,
                distinctDomain = distinctDomain,
                distinctAuthor = distinctAuthor,
            )
        }
    }
}
