package com.greenart7c3.citrine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.server.sip01.WebDocument
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Byte-compatibility tests for the SIP-01 implementation (normalization §7,
 * document identity §3, content identity §8, ingestion validation §12.4).
 *
 * Every hash in this file comes from the canonical specification's §13 test
 * vectors (https://github.com/NostrDanish/SIP-01, public/spec/SIP-01.md) and is
 * shared with every other ecosystem implementation — a single character of
 * drift breaks deduplication across the network.
 */
class WebDocumentTest {
    private val mapper = jacksonObjectMapper()

    // Build via the same parse path the relay uses on untrusted wire data:
    // Event.fromJson never verifies id/signature, so dummy ids are fine.
    private fun webDocEvent(
        tags: Array<Array<String>>,
        content: String = "{\"title\":\"Example\"}",
        kind: Int = WebDocument.WEB_DOCUMENT_KIND,
    ): Event {
        val json = mapper.writeValueAsString(
            mapOf(
                "id" to "a".repeat(64),
                "pubkey" to "b".repeat(64),
                "created_at" to 1_786_250_000L,
                "kind" to kind,
                "tags" to tags,
                "content" to content,
                "sig" to "c".repeat(128),
            ),
        )
        return Event.fromJson(json)
    }

    private fun validTags(url: String = "https://example.com/page"): Array<Array<String>> {
        val d = WebDocument.webDocumentDTag(WebDocument.normalizeIndexUrl(url)!!)
        return arrayOf(
            arrayOf("d", d),
            arrayOf("u", url),
            arrayOf("v", "1"),
            arrayOf("alt", "Web index observation: Example"),
        )
    }

    // ------------------------------------------------------------------
    // §13.1 URL identity vectors
    // ------------------------------------------------------------------

    @Test
    fun urlNormalizationVector1() {
        assertEquals("https://example.com/", WebDocument.normalizeIndexUrl("https://example.com/"))
        assertEquals(
            "widx:0f115db062b7c0dd030b16878c99dea5",
            WebDocument.webDocumentDTag("https://example.com/"),
        )
    }

    @Test
    fun urlNormalizationVector2() {
        val normalized = WebDocument.normalizeIndexUrl("HTTPS://WWW.Example.Com:443/page/?b=2&utm_source=x&a=1#top")
        assertEquals("https://example.com/page?a=1&b=2", normalized)
        assertEquals(
            "widx:f68176b3eb966bd682c3c6eadcc5fe44",
            WebDocument.webDocumentDTag(normalized!!),
        )
    }

    @Test
    fun urlNormalizationVector3() {
        assertEquals("https://example.com/page", WebDocument.normalizeIndexUrl("https://example.com/page"))
        assertEquals(
            "widx:3641c5f2274c5471278ab5bf1df6d185",
            WebDocument.webDocumentDTag("https://example.com/page"),
        )
    }

    @Test
    fun urlNormalizationVector4() {
        // The path is case-sensitive — only scheme and host are lowercased.
        assertEquals(
            "https://github.com/NostrDanish/Crwalstr",
            WebDocument.normalizeIndexUrl("https://github.com/NostrDanish/Crwalstr"),
        )
        assertEquals(
            "widx:cdfd4df8c01d609fc9cdf943afa80197",
            WebDocument.webDocumentDTag("https://github.com/NostrDanish/Crwalstr"),
        )
    }

    // ------------------------------------------------------------------
    // §13.2 Content identity vectors
    // ------------------------------------------------------------------

    @Test
    fun contentHashVector1() {
        assertEquals(
            "e1762f14d9924e37b32f1c81dfd256410af462f5136415c96877efa8c80345d0",
            WebDocument.webDocumentContentHash("Example", null),
        )
    }

    @Test
    fun contentHashVector2() {
        assertEquals(
            "2a5cbdf44513f552fb571d6c6de2ddf16c5452b235cc887980b52898fb38e7c1",
            WebDocument.webDocumentContentHash("Example Page", "A page about examples."),
        )
    }

    // ------------------------------------------------------------------
    // §7 normalization rules
    // ------------------------------------------------------------------

    @Test
    fun normalizationRejectsNonHttpSchemes() {
        assertNull(WebDocument.normalizeIndexUrl("ftp://example.com/"))
        assertNull(WebDocument.normalizeIndexUrl("javascript:alert(1)"))
        assertNull(WebDocument.normalizeIndexUrl("data:text/html,<h1>"))
        assertNull(WebDocument.normalizeIndexUrl("file:///etc/passwd"))
        assertNull(WebDocument.normalizeIndexUrl("not a url"))
        assertNull(WebDocument.normalizeIndexUrl(""))
    }

    @Test
    fun normalizationStripsAllTrackingParamsCaseInsensitively() {
        val trackers = listOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "fbclid", "gclid", "dclid", "mc_cid", "mc_eid", "igshid", "ref_src",
            "spm", "si",
        )
        val query = trackers.joinToString("&") { "$it=x" }
        assertEquals(
            "https://example.com/",
            WebDocument.normalizeIndexUrl("https://example.com/?$query"),
        )
        // Uppercase tracker names are trackers too.
        assertEquals(
            "https://example.com/",
            WebDocument.normalizeIndexUrl("https://example.com/?UTM_SOURCE=x"),
        )
    }

    @Test
    fun normalizationPreservesSemanticParamsAndSortsStably() {
        assertEquals(
            "https://example.com/?id=1&page=2&q=nostr",
            WebDocument.normalizeIndexUrl("https://example.com/?q=nostr&page=2&id=1"),
        )
        // Duplicate keys keep their relative order (stable sort).
        assertEquals(
            "https://example.com/?a=1&a=2",
            WebDocument.normalizeIndexUrl("https://example.com/?a=1&a=2"),
        )
    }

    @Test
    fun normalizationHandlesPortsPathsAndFragments() {
        // Non-default ports survive.
        assertEquals(
            "https://example.com:8443/page",
            WebDocument.normalizeIndexUrl("https://example.com:8443/page"),
        )
        // Default http port removed.
        assertEquals(
            "http://example.com/page",
            WebDocument.normalizeIndexUrl("http://example.com:80/page"),
        )
        // Bare root keeps its slash; deeper paths lose a trailing one.
        assertEquals("https://example.com/", WebDocument.normalizeIndexUrl("https://example.com"))
        assertEquals("https://example.com/a", WebDocument.normalizeIndexUrl("https://example.com/a/"))
        // Fragment removed.
        assertEquals("https://example.com/a", WebDocument.normalizeIndexUrl("https://example.com/a#section"))
        // Bare trailing "?" normalized away.
        assertEquals("https://example.com/", WebDocument.normalizeIndexUrl("https://example.com/?"))
    }

    @Test
    fun normalizationReencodesQueryParamsPerWhatwg() {
        // Space encodes as '+', reserved chars percent-encode uppercase.
        assertEquals(
            "https://example.com/?q=hello+world",
            WebDocument.normalizeIndexUrl("https://example.com/?q=hello%20world"),
        )
    }

    // ------------------------------------------------------------------
    // §12.4 ingestion validation
    // ------------------------------------------------------------------

    @Test
    fun validationAcceptsMinimalValidEvent() {
        // The spec §19 minimal example shape (recomputed d for the fixed u).
        val event = webDocEvent(validTags())
        assertNull(WebDocument.validateWebDocument(event))
    }

    @Test
    fun validationAcceptsFullEventWithExtensions() {
        val url = "https://github.com/NostrDanish/Crwalstr"
        val d = WebDocument.webDocumentDTag(WebDocument.normalizeIndexUrl(url)!!)
        val title = "Crwalstr — a browser-based web crawler for Nostr"
        val description = "A browser-based web crawler that publishes SIP-01 web index observations."
        val x = WebDocument.webDocumentContentHash(title, description)
        val event = webDocEvent(
            tags = arrayOf(
                arrayOf("d", d),
                arrayOf("u", url),
                arrayOf("t", "nostr"),
                arrayOf("t", "crawler"),
                arrayOf("l", "en"),
                arrayOf("x", x),
                arrayOf("v", "1"),
                arrayOf("published", "1786200000"),
                arrayOf("type", "repository"),
                arrayOf("platform", "github"),
                arrayOf("network", "clearnet"),
                arrayOf("source", "crawlstr/1"),
                arrayOf("alt", "Web index observation: $title"),
                // Unknown tags are ignored (§9.1.3 forwards compatibility).
                arrayOf("x-experiment", "anything"),
            ),
            content = "{\"title\":\"$title\",\"description\":\"$description\"}",
        )
        assertNull(WebDocument.validateWebDocument(event))
    }

    @Test
    fun validationIgnoresOtherKinds() {
        val event = webDocEvent(tags = arrayOf(), content = "hello", kind = 1)
        assertNull(WebDocument.validateWebDocument(event))
    }

    @Test
    fun validationRejectsMissingRequiredTags() {
        assertEquals(
            "web document missing d tag",
            WebDocument.validateWebDocument(
                webDocEvent(validTags().filter { it[0] != "d" }.toTypedArray()),
            ),
        )
        assertEquals(
            "web document missing u tag",
            WebDocument.validateWebDocument(
                webDocEvent(validTags().filter { it[0] != "u" }.toTypedArray()),
            ),
        )
        assertEquals(
            "web document missing v tag",
            WebDocument.validateWebDocument(
                webDocEvent(validTags().filter { it[0] != "v" }.toTypedArray()),
            ),
        )
        assertEquals(
            "web document missing alt tag",
            WebDocument.validateWebDocument(
                webDocEvent(validTags().filter { it[0] != "alt" }.toTypedArray()),
            ),
        )
    }

    @Test
    fun validationRejectsDuplicateSingletonTags() {
        assertEquals(
            "web document has multiple d tags",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("d", "widx:deadbeef")))),
        )
        assertEquals(
            "web document has multiple u tags",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("u", "https://example.com/other")))),
        )
        assertEquals(
            "web document has multiple v tags",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("v", "1")))),
        )
        assertEquals(
            "web document has multiple alt tags",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("alt", "second")))),
        )
    }

    @Test
    fun validationRejectsUnsupportedSchemaVersion() {
        val tags = validTags().map { if (it[0] == "v") arrayOf("v", "2") else it }.toTypedArray()
        assertEquals(
            "unsupported web document schema version \"2\"",
            WebDocument.validateWebDocument(webDocEvent(tags)),
        )
    }

    @Test
    fun validationRejectsInvalidUrlAndDMismatch() {
        val badUrlTags = validTags("notaurl")
        assertEquals(
            "u tag is not a valid http(s) URL",
            WebDocument.validateWebDocument(webDocEvent(badUrlTags)),
        )

        val wrongD = validTags().map {
            if (it[0] == "d") arrayOf("d", "widx:00000000000000000000000000000000") else it
        }.toTypedArray()
        assertEquals(
            "d tag does not match the normalized u tag (widx: + sha256(u)[0:32])",
            WebDocument.validateWebDocument(webDocEvent(wrongD)),
        )
    }

    @Test
    fun validationRejectsBadContent() {
        assertEquals(
            "web document content is not valid JSON with a title",
            WebDocument.validateWebDocument(webDocEvent(validTags(), content = "not json")),
        )
        assertEquals(
            "web document content is not valid JSON with a title",
            WebDocument.validateWebDocument(webDocEvent(validTags(), content = "{\"description\":\"no title\"}")),
        )
        assertEquals(
            "web document content is not valid JSON with a title",
            WebDocument.validateWebDocument(webDocEvent(validTags(), content = "[\"array\"]")),
        )
        assertEquals(
            "title must be 1-300 characters",
            WebDocument.validateWebDocument(webDocEvent(validTags(), content = "{\"title\":\"   \"}")),
        )
        assertEquals(
            "title must be 1-300 characters",
            WebDocument.validateWebDocument(webDocEvent(validTags(), content = "{\"title\":\"${"x".repeat(301)}\"}")),
        )
        assertEquals(
            "description exceeds 1000 characters",
            WebDocument.validateWebDocument(
                webDocEvent(validTags(), content = "{\"title\":\"T\",\"description\":\"${"x".repeat(1001)}\"}"),
            ),
        )
    }

    @Test
    fun validationRejectsNonHttpsImage() {
        assertEquals(
            "image must be an https URL",
            WebDocument.validateWebDocument(
                webDocEvent(validTags(), content = "{\"title\":\"T\",\"image\":\"http://example.com/i.png\"}"),
            ),
        )
    }

    @Test
    fun validationRejectsBadTopicTags() {
        val nine = (1..9).map { arrayOf("t", "topic$it") }.toTypedArray()
        assertEquals(
            "web document has more than 8 topic tags",
            WebDocument.validateWebDocument(webDocEvent(validTags() + nine)),
        )
        assertEquals(
            "topic (t) tags must be lowercase alphanumeric words",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("t", "Nostr")))),
        )
        assertEquals(
            "topic (t) tags must be lowercase alphanumeric words",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("t", "has space")))),
        )
    }

    @Test
    fun validationRejectsBadLanguageAndX() {
        assertEquals(
            "l tag is not a valid ISO 639-1 language code",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("l", "EN")))),
        )
        assertEquals(
            "x tag must be a lowercase hex sha256 digest",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("x", "zz")))),
        )
        val wrongX = "0".repeat(64)
        assertEquals(
            "x tag does not match sha256(title + \\n + description)",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("x", wrongX)))),
        )
    }

    @Test
    fun validationRejectsBadPublishedSourceCountryMime() {
        assertEquals(
            "published tag must be a unix timestamp in seconds",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("published", "yesterday")))),
        )
        assertEquals(
            "source tag exceeds 100 characters",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("source", "x".repeat(101))))),
        )
        assertEquals(
            "country tag must be an ISO 3166-1 alpha-2 code",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("country", "DEN")))),
        )
        assertEquals(
            "mime tag is not a valid MIME type",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("mime", "not a mime")))),
        )
    }

    @Test
    fun validationRejectsBadExtensionKeywords() {
        assertEquals(
            "type tag is not a valid keyword",
            WebDocument.validateWebDocument(webDocEvent(validTags() + arrayOf(arrayOf("type", "not a keyword")))),
        )
    }

    // ------------------------------------------------------------------
    // Field extraction (relay profile §2)
    // ------------------------------------------------------------------

    @Test
    fun extractionProducesStructuredFields() {
        val url = "https://Docs.GitHub.com/NostrDanish/Crwalstr?utm_source=x"
        val normalized = WebDocument.normalizeIndexUrl(url)!!
        assertEquals("https://docs.github.com/NostrDanish/Crwalstr", normalized)
        val d = WebDocument.webDocumentDTag(normalized)
        val title = "Crwalstr"
        val description = "A crawler."
        val event = webDocEvent(
            tags = arrayOf(
                arrayOf("d", d),
                arrayOf("u", url),
                arrayOf("l", "en"),
                arrayOf("x", WebDocument.webDocumentContentHash(title, description)),
                arrayOf("v", "1"),
                arrayOf("published", "1786200000"),
                arrayOf("type", "Repository"), // invalid shape? no — keyword allows mixed case
                arrayOf("platform", "github"),
                arrayOf("network", "clearnet"),
                arrayOf("country", "dk"),
                arrayOf("mime", "TEXT/HTML"),
                arrayOf("source", "crawlstr/1"),
                arrayOf("alt", "Web index observation: $title"),
            ),
            content = "{\"title\":\"$title\",\"description\":\"$description\"}",
        )
        val fields = WebDocument.extractWebDocumentFields(event)
        assertNotNull(fields)
        fields!!
        assertEquals(normalized, fields.url)
        assertEquals("docs.github.com", fields.urlHost)
        assertEquals(d, fields.dTag)
        assertEquals(title, fields.title)
        assertEquals(description, fields.description)
        assertEquals("en", fields.language)
        assertEquals(1786200000L, fields.publishedAt)
        assertEquals(1786250000L, fields.observedAt)
        assertEquals("repository", fields.docType) // lowercased at index time
        assertEquals("github", fields.platform)
        assertEquals("clearnet", fields.network)
        assertEquals("DK", fields.country) // uppercased at index time
        assertEquals("text/html", fields.mime)
        assertEquals("crawlstr/1", fields.source)
        assertNull(fields.fileExt)
    }

    @Test
    fun extractionFileExtension() {
        assertEquals("html", WebDocument.fileExtension("/a/page.html"))
        assertEquals("gz", WebDocument.fileExtension("/a/file.tar.gz"))
        assertNull(WebDocument.fileExtension("/a/.dotfile"))
        assertNull(WebDocument.fileExtension("/a/noextension"))
        assertNull(WebDocument.fileExtension("/a/trailing."))
    }

    @Test
    fun domainHierarchyIncludesParents() {
        assertEquals(listOf("docs.github.com", "github.com"), WebDocument.domainHierarchy("docs.github.com"))
        assertEquals(listOf("github.com"), WebDocument.domainHierarchy("github.com"))
        assertEquals(listOf("1.2.3.4"), WebDocument.domainHierarchy("1.2.3.4"))
        assertEquals(listOf("localhost"), WebDocument.domainHierarchy("localhost"))
    }

    @Test
    fun searchHostValueForgivesCommonForms() {
        assertEquals("github.com", WebDocument.searchHostValue("https://github.com/x"))
        assertEquals("github.com", WebDocument.searchHostValue("GitHub.com."))
        assertEquals("github.com", WebDocument.searchHostValue("www.github.com"))
        assertEquals("github.com", WebDocument.searchHostValue("github.com/torvalds"))
        assertNull(WebDocument.searchHostValue(""))
        assertNull(WebDocument.searchHostValue("not a host"))
    }
}
