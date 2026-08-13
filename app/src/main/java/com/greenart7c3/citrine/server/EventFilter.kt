package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.server.sip01.Sip01Search
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.util.function.Predicate

data class EventFilter(
    val ids: Set<String> = emptySet(),
    val authors: Set<String> = emptySet(),
    val kinds: Set<Int> = emptySet(),
    val tags: Map<String, Set<String>> = emptyMap(),
    var since: Int? = null,
    val until: Int? = null,
    val limit: Int? = null,
    val search: String? = null,
) : Predicate<Event> {
    /**
     * The NIP-50 search string parsed once into SIP-01 web-search operators
     * (`site:`, `domain:`, `url:`, `inurl:`, `title:`, `topic:`, …) and
     * free-text terms. `textTerms`/`notTextTerms` replace [searchKeywords] so
     * operator tokens never leak into the FTS query and `-term` negation works.
     */
    val sip01Search: Sip01Search = Sip01Search.parse(search)

    val searchKeywords: Set<String> = sip01Search.textTerms.toSet()

    override fun test(event: Event): Boolean {
        // Cheapest checks first: this runs against every active subscription
        // for every event inserted into the database.
        if (kinds.isNotEmpty() && event.kind !in kinds) {
            return false
        }

        if (since != null && event.createdAt < since!!) {
            return false
        }

        if (until != null && event.createdAt > until) {
            return false
        }

        // O(1) exact match first; fall back to the O(n) prefix scan only on miss.
        if (ids.isNotEmpty() && event.id !in ids && ids.none { event.id.startsWith(it) }) {
            return false
        }

        if (authors.isNotEmpty() && event.pubKey !in authors && authors.none { event.pubKey.startsWith(it) }) {
            return false
        }

        if (tags.isNotEmpty() && !tags.all { testTag(it, event) }) {
            return false
        }

        if ((searchKeywords.isNotEmpty() || sip01Search.hasSearchClauses) && !sip01Search.matches(event)) {
            return false
        }

        return true
    }

    private fun testTag(tag: Map.Entry<String, Set<String>>, event: Event): Boolean = event.tags.any { it.size > 1 && it[0] == tag.key && it[1] in tag.value }

    companion object {
        val TOKENIZE_REGEX = "[^a-zA-Z0-9]".toRegex()
    }
}
