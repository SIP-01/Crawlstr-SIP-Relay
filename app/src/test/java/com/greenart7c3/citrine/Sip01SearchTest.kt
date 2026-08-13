package com.greenart7c3.citrine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventRepository
import com.greenart7c3.citrine.server.sip01.Sip01Search
import com.greenart7c3.citrine.server.sip01.WebDocument
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the SIP-01 NIP-50 web-search operators (relay profile §4): parsing,
 * in-memory matching for live subscription fan-out, and SQL translation.
 */
class Sip01SearchTest {
    private val mapper = jacksonObjectMapper()

    private fun webDocEvent(
        url: String,
        title: String = "Example",
        description: String = "A page about examples.",
        extraTags: Array<Array<String>> = emptyArray(),
    ): Event {
        val d = WebDocument.webDocumentDTag(WebDocument.normalizeIndexUrl(url)!!)
        val json = mapper.writeValueAsString(
            mapOf(
                "id" to "a".repeat(64),
                "pubkey" to "b".repeat(64),
                "created_at" to 1_786_250_000L,
                "kind" to WebDocument.WEB_DOCUMENT_KIND,
                "tags" to (
                    arrayOf(
                        arrayOf("d", d),
                        arrayOf("u", url),
                        arrayOf("v", "1"),
                        arrayOf("alt", "Web index observation: $title"),
                    ) + extraTags
                    ),
                "content" to "{\"title\":\"$title\",\"description\":\"$description\"}",
                "sig" to "c".repeat(128),
            ),
        )
        // Event.fromJson never verifies id/signature, so dummy values are fine.
        return Event.fromJson(json)
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    fun parsesFreeTextAndOperators() {
        val parsed = Sip01Search.parse("bitcoin privacy site:github.com lang:en after:2026-01-01")
        assertEquals(setOf("bitcoin", "privacy"), parsed.textTerms.toSet())
        assertEquals(listOf("github.com"), parsed.site)
        assertEquals(listOf("en"), parsed.lang)
        // 2026-01-01 00:00:00 UTC
        assertEquals(1767225600L, parsed.after)
        assertNull(parsed.before)
        assertTrue(parsed.requiresWebIndexJoin)
    }

    @Test
    fun parsesNegatedOperators() {
        val parsed = Sip01Search.parse("-site:spam.com -topic:nft bitcoin")
        assertEquals(listOf("spam.com"), parsed.notSite)
        assertEquals(listOf("nft"), parsed.notTopic)
        assertEquals(listOf("bitcoin"), parsed.textTerms)
    }

    @Test
    fun ignoresUnknownOperatorsPerNip50() {
        val parsed = Sip01Search.parse("bitcoin pow:21 sort:top")
        assertEquals(listOf("bitcoin"), parsed.textTerms)
        assertFalse(parsed.requiresWebIndexJoin)
    }

    @Test
    fun normalizesOperatorValues() {
        val parsed = Sip01Search.parse(
            "site:HTTPS://WWW.GitHub.Com/x type:Repository country:dk filetype:.PDF mime:TEXT/HTML url:https://WWW.Example.COM/page?utm_source=x",
        )
        assertEquals(listOf("github.com"), parsed.site)
        assertEquals(listOf("repository"), parsed.type)
        assertEquals(listOf("DK"), parsed.country)
        assertEquals(listOf("pdf"), parsed.filetype)
        assertEquals(listOf("text/html"), parsed.mime)
        // url: values are normalized with the same §7 rules used at index time.
        assertEquals("https://example.com/page", parsed.url)
    }

    @Test
    fun parsesQuotedPhrasesIntoWords() {
        val parsed = Sip01Search.parse("\"bitcoin privacy\" title:\"hello world\"")
        // Phrases split back into individual FTS terms (legacy behavior)…
        assertEquals(listOf("bitcoin", "privacy"), parsed.textTerms)
        // …while quoted operator values keep their space.
        assertEquals(listOf("hello world"), parsed.title)
    }

    @Test
    fun parsesDistinctAndDates() {
        val parsed = Sip01Search.parse("distinct:domain before:1786300000 after:2026-01-01")
        assertTrue(parsed.distinctDomain)
        assertFalse(parsed.distinctAuthor)
        assertEquals(1786300000L, parsed.before)
        assertEquals(1767225600L, parsed.after)

        val bad = Sip01Search.parse("before:not-a-date")
        assertNull(bad.before)
    }

    @Test
    fun emptySearchHasNoClauses() {
        val parsed = Sip01Search.parse(null)
        assertFalse(parsed.hasSearchClauses)
        assertFalse(parsed.requiresWebIndexJoin)
        val blank = Sip01Search.parse("   ")
        assertFalse(blank.hasSearchClauses)
    }

    // ------------------------------------------------------------------
    // In-memory matching (live subscription fan-out path)
    // ------------------------------------------------------------------

    @Test
    fun matchesSiteAndDomainSemantics() {
        val sub = webDocEvent("https://docs.github.com/page")
        assertTrue(Sip01Search.parse("site:github.com").matches(sub))
        assertFalse(Sip01Search.parse("domain:github.com").matches(sub))
        assertTrue(Sip01Search.parse("domain:docs.github.com").matches(sub))
        assertTrue(Sip01Search.parse("-site:gitlab.com").matches(sub))
        assertFalse(Sip01Search.parse("-site:github.com").matches(sub))
    }

    @Test
    fun matchesTitleInurlAndText() {
        val event = webDocEvent("https://example.com/bitcoin-guide", title = "Bitcoin Guide")
        assertTrue(Sip01Search.parse("title:bitcoin").matches(event))
        assertTrue(Sip01Search.parse("inurl:guide").matches(event))
        assertFalse(Sip01Search.parse("title:ethereum").matches(event))
        assertTrue(Sip01Search.parse("bitcoin").matches(event)) // free text via content
        assertFalse(Sip01Search.parse("-bitcoin").matches(event))
    }

    @Test
    fun matchesKeywordOperatorsAndDates() {
        val event = webDocEvent(
            "https://github.com/some/proj",
            extraTags = arrayOf(
                arrayOf("t", "nostr"),
                arrayOf("l", "en"),
                arrayOf("type", "repository"),
                arrayOf("platform", "github"),
                arrayOf("network", "clearnet"),
                arrayOf("country", "DK"),
                arrayOf("mime", "text/html"),
                arrayOf("source", "crawlstr/1"),
                arrayOf("published", "1786200000"),
            ),
        )
        assertTrue(Sip01Search.parse("topic:nostr").matches(event))
        assertFalse(Sip01Search.parse("topic:bitcoin").matches(event))
        assertTrue(Sip01Search.parse("type:repository platform:github network:clearnet").matches(event))
        assertTrue(Sip01Search.parse("country:dk").matches(event)) // query side forgives case
        assertTrue(Sip01Search.parse("mime:text/html source:crawlstr/1").matches(event))
        assertTrue(Sip01Search.parse("lang:en").matches(event))
        assertTrue(Sip01Search.parse("after:2026-08-01").matches(event))
        assertFalse(Sip01Search.parse("before:2026-08-01").matches(event))
        // Events without a published tag don't match date ranges.
        val noDate = webDocEvent("https://github.com/other")
        assertFalse(Sip01Search.parse("after:2026-01-01").matches(noDate))
    }

    @Test
    fun operatorsNeverMatchNonWebDocuments() {
        val kind1 = Event.fromJson(
            mapper.writeValueAsString(
                mapOf(
                    "id" to "a".repeat(64),
                    "pubkey" to "b".repeat(64),
                    "created_at" to 1_786_250_000L,
                    "kind" to 1,
                    "tags" to emptyArray<Array<String>>(),
                    "content" to "bitcoin site:github.com",
                    "sig" to "c".repeat(128),
                ),
            ),
        )
        assertFalse(Sip01Search.parse("site:github.com").matches(kind1))
        // Pure free text still matches any kind.
        assertTrue(Sip01Search.parse("bitcoin").matches(kind1))
    }

    // ------------------------------------------------------------------
    // SQL translation (stored query path)
    // ------------------------------------------------------------------

    private fun sqlFor(search: String, kinds: Set<Int> = setOf(39697)): Pair<String, List<Any>> = EventRepository.createQuery(
        EventFilter(kinds = kinds, search = search),
        EventRepository.SelectMode.FULL_EVENTS,
    )

    @Test
    fun sqlJoinsWebIndexForOperators() {
        val (sql, params) = sqlFor("bitcoin site:github.com")
        assertTrue(sql.contains("INNER JOIN WebIndexEntity"))
        assertTrue(sql.contains("INNER JOIN event_fts"))
        assertTrue(sql.contains("WebIndexEntity.host = ?"))
        assertTrue(sql.contains("ESCAPE"))
        assertTrue(params.contains("github.com"))
        assertTrue(params.any { it.toString().contains("bitcoin") })
    }

    @Test
    fun sqlNoJoinForPureText() {
        val (sql, _) = sqlFor("bitcoin")
        assertFalse(sql.contains("WebIndexEntity"))
        assertTrue(sql.contains("event_fts MATCH ?"))
    }

    @Test
    fun sqlNegationAndKeywordOperators() {
        val (sql, params) = sqlFor("-site:spam.com type:repository country:DK before:2027-01-01")
        assertTrue(sql.contains("NOT ("))
        assertTrue(sql.contains("WebIndexEntity.docType IN (?)"))
        assertTrue(sql.contains("WebIndexEntity.country IN (?)"))
        assertTrue(sql.contains("WebIndexEntity.publishedAt < ?"))
        assertTrue(params.contains("spam.com"))
        assertTrue(params.contains("repository"))
        assertTrue(params.contains("DK"))
    }

    @Test
    fun sqlDistinctDomainGroupsByHost() {
        val (sql, _) = sqlFor("bitcoin distinct:domain")
        assertTrue(sql.contains("GROUP BY WebIndexEntity.host"))
        assertTrue(sql.contains("MAX(EventEntity.createdAt)"))
    }

    @Test
    fun sqlDistinctAuthorGroupsByPubkeyWithoutJoin() {
        val (sql, _) = sqlFor("distinct:author")
        assertTrue(sql.contains("GROUP BY EventEntity.pubkey"))
        assertFalse(sql.contains("WebIndexEntity"))
    }

    @Test
    fun sqlLikeWildcardsAreEscaped() {
        val (_, params) = sqlFor("inurl:100%_x")
        assertTrue(params.contains("100\\%\\_x"))
    }

    @Test
    fun sqlUrlOperatorUsesNormalizedValue() {
        val (_, params) = sqlFor("url:HTTPS://WWW.Example.COM/page?utm_source=x")
        assertTrue(params.contains("https://example.com/page"))
    }

    @Test
    fun sqlTopicOperatorUsesTagStore() {
        val (sql, params) = sqlFor("topic:nostr")
        assertTrue(sql.contains("col0Name = 't'"))
        assertTrue(params.contains("nostr"))
    }

    @Test
    fun sqlBeforeAfterUsePublishedAt() {
        val (sql, params) = sqlFor("after:2026-01-01 before:1786300000")
        assertTrue(sql.contains("WebIndexEntity.publishedAt >= ?"))
        assertTrue(sql.contains("WebIndexEntity.publishedAt < ?"))
        assertTrue(params.contains(1767225600L))
        assertTrue(params.contains(1786300000L))
    }

    @Test
    fun sqlNotTextTermsBecomeFtsNot() {
        val (sql, params) = sqlFor("bitcoin -spam")
        assertTrue(sql.contains("event_fts MATCH ?"))
        assertTrue(params.any { it.toString() == "\"bitcoin\" NOT \"spam\"" })
    }
}
