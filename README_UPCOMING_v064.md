# Demos Manga v0.6.4 — reliable Upcoming logic

- Distinguish estimates of **source checks** from confirmed publisher
  chapter dates. No guaranteed release claims.
- **Eligible for update** follows the same category, status, unread,
  not-started and allowed source-check-window rules as LibraryUpdateJob.
- Fix inverse overdue check: overdue titles remain actionable while
  future checks outside a restricted release window are excluded.
- The eligible list updates when the library changes.
- No fake 1970 date for unscheduled Manga.
- Source check cadence is inferred from genuine chapter upload dates,
  never dates the app happened to fetch those chapters. Missing publisher
  dates receive a conservative seven-day check interval.
- Upcoming calendar estimates source checks; eligible entries have no
  fabricated calendar dates.
- Original chapter flags, source IDs, category assignments, tracking and
  Demos Manga/Cinema private sync remain intact.
- Real-device QA remains pending. Kotlin tests and hosted CI must pass
  before publishing a signed official update.
