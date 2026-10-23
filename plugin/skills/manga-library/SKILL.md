---
name: manga-library
description: Manage and summarize the user's own synced manga reading progress through Demos Manga MCP.
---

Use Demos Manga's `library_stats` and `search_library` MCP tools to summarize the user's existing library, count unread chapters, and find books by title. If `has_sync_data` is false, say the Android app has not synced with the bridge yet. Never claim data is current without checking. Do not fetch chapter images, pages, sources, or private credentials. Respect reading data privacy and only make user-requested changes; tools in this version are read-only.
