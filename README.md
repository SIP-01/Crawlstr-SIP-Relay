# Crawlstr SIP Relay: a mobile indexing relay for Nostr

A fork of [Citrine](https://github.com/greenart7c3/Citrine) (the Nostr relay for Android) that turns your phone into a **validating index relay and search node for [SIP-01](https://github.com/NostrDanish/SIP-01)** — the Decentralized Search Index Protocol on Nostr.

One shared decentralized index. Many independent indexers. Many independent search engines. No single owner — and now the index fits in your pocket.

# SIP-01 support

Crawlers like [Crawlstr](https://github.com/NostrDanish/Crwalstr) and [indexstr](https://github.com/NostrDanish/indexstr) publish signed **kind 39697 web index observations**; this relay validates, indexes, replicates, and serves them — the same relay profile as the [UNCAGED Index Relay](https://github.com/NostrDanish/UNCAGED-Index-Relay), on Android:

- [x] **Ingestion validation (SIP-01 §12.4)** — malformed observations are rejected with `OK false invalid: …`: schema version, `d` ↔ normalized-`u` consistency, `x` ↔ content-hash consistency, URL allowlist, and all hard length caps are enforced at the door
- [x] **Byte-compatible URL normalization & hashing** (§7, §3, §8) — the spec's §13 test vectors run as unit tests (`WebDocumentTest`), so this relay deduplicates identically to every other implementation
- [x] **Structured indexing (relay profile §2)** — observations are extracted into dedicated fields (`WebIndexEntity`: url, host, file extension, title, description, language, content hash, published/observed/first-seen timestamps, source, and the `type`/`platform`/`category`/`network`/`country`/`mime` extension tags) while the signed event stays the source of truth
- [x] **NIP-50 web-search operators (§4)** — `site:`, `domain:`, `url:`, `inurl:`, `title:`, `topic:`, `type:`, `platform:`, `category:`, `network:`, `country:`, `mime:`, `filetype:`, `source:`, `lang:`, `before:`, `after:`, `distinct:domain` / `distinct:author`, each filter with a negated `-op:` form
- [x] **NIP-11 capability advertisement (§5)** — the relay information document carries the `uncaged_index` block (`sip01`, `nip50`, `nip77`, document kinds, operator-declared scope/domains/languages/document types)
- [x] **Index replication (§6 federation)** — optional sync subscribes to kind 39697 on peer index relays, so the phone keeps a live, fully searchable copy of the shared index even offline; WiFi-only and pause-on-limited-network modes included
- [x] Plain NIP-01 filters (`kinds`, `#d`, `#t`, `#u`, `#x`, `authors`, `since`/`until`) keep working against the same stored events

Example search, straight from any Nostr client connected to the phone:

```json
["REQ", "search", {
  "kinds": [39697],
  "search": "bitcoin privacy site:github.com lang:en after:2026-01-01",
  "limit": 50
}]
```

# Current Features (Citrine upstream)

- [x] Export database
- [x] Import database
- [x] Export only events from your account
- [x] Manage the relay (port, icon, user, start, stop)
- [x] Broadcast events after coming back online
- [x] Add content resolvers

# Download

[<img src="https://github.com/greenart7c3/Amber/blob/master/assets/zapstore.svg"
alt="Get it on Zap Store"
height="70">](https://zapstore.dev/apps/naddr1qvzqqqr7pvpzqateqake4lc2fn77lflzq30jfpk8uhvtccalc66989er8cdmljceqqtkxmmd9enhyet9deshyaphvvejucmfw3exjmn9hzj8uf)
[<img src="https://github.com/greenart7c3/Amber/blob/master/assets/obtainium.png"
alt="Get it on Obtaininum"
height="70">](https://github.com/ImranR98/Obtainium)
[<img src="https://github.com/machiav3lli/oandbackupx/raw/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub"
height="70">](https://github.com/greenart7c3/Citrine/releases)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
alt="Get it on F-Droid"
height="70">](https://f-droid.org/packages/com.greenart7c3.citrine/)

# Contributing

[Issues](https://github.com/greenart7c3/Citrine/issues) and [pull requests](https://github.com/greenart7c3/Citrine/pulls) are very welcome.

# Contributors

<a align="center" href="https://github.com/greenart7c3/Citrine/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=greenart7c3/Citrine" />
</a>
