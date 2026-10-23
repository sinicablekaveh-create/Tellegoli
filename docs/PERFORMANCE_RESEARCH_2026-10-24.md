# Tellegoli 0.12.0: performance research (2026-10-24)

## Audit scope and status
Examined 24 Exa search results in three workstreams (Android WorkManager, official TDLib/public-chat limitations, web/SQLite performance), checked primary references, and reviewed the project source. Changes below are committed to the existing draft branch, **not yet Codemagic-built, linted, or benchmarked on device**.

## Findings
1. **Bounded inter-host concurrency**: Up to 3 public company websites scan concurrently. Within a host, requests remain sequential with robots.txt, crawl delay, limit and retry protections. Number 3 is an initial safety ceiling, not a proven optimum.
2. **First-run delay**: When enabled, Android WorkManager queues a one-time request plus a regular 15-minute periodic worker. The one-time request is eligible to start immediately but not guaranteed to do so. Telegram needs an existing READY TDLib session and explicit opt-in. Do not claim that Doze or Force Stop can be bypassed.
3. **Telegram groups**: Official TDLib `searchPublicChats` searches public chat titles/usernames. It does **not** provide an unrestricted source of member phone numbers. Too many public-chat searches risk flood waits; keep keyword rotation, daily checkpoints and backoff, not parallel account searches.
4. **Phone-number bottleneck to address next**: `MainActivity.Store.insertPublicBusinessPhone` scans every existing phone to normalize legacy Persian/Arabic digits before deduplication. This is O(n) work for each new number and can become a bottleneck as the DB grows. Safest future fix is a migration to a normalized phone key with a unique SQLite index, tested with existing data, rather than dropping records or disabling duplicate checks.
5. **Early contact-page priority**: The URL queue now prefers a site's public contact/about pages while preserving host and source validation.
6. **Measurement**: Each discovery run now reports `elapsed_ms`. Compare old/new builds on the same device using pages per minute, newly verified business phones per run, duplicates, errors, completed group queries and battery use. Never infer 2x/3x speed without benchmarks.
7. **Independent build**: Codemagic workflow continues to run `-PecStandaloneTest=true testDebugUnitTest lintDebug assembleDebug`, verifying sandbox app ID `ir.electricconnect.tellegoli.sandbox`. Existing installs remain untouched.

## Codemagic acceptance
- All unit tests pass, Android Lint passes, assembleDebug creates a verified APK and logs.
- Two distinct public hosts overlap in the test, while each site still honors sequential request policy.
- Check start/stop, intermittent network, source contact-page priority and stored-phone deduplication.
- On a phone with TDLib READY, check actual background first scan, periodic attempts, rate limits and interruption behavior.
- Discovery-only test must not send messages, join chats or fetch members.

## Primary sources
- Android: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- SQLite: https://developer.android.com/topic/performance/sqlite-performance-best-practices
- TDLib search: https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1search_public_chats.html
- TDLib flood-wait maintainer discussion: https://github.com/tdlib/td/issues/1027
