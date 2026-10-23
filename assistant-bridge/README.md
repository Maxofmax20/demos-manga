# Demos Manga private assistant bridge

This bridge is a **SyncYomi-compatible, opt-in receiver** for a personal Komikku-derived Android reader. It stores one protobuf backup and exposes **read-only library metadata**, never chapter pages, downloaded files, tracker tokens, notes, or app preferences, through an MCP endpoint.

## Endpoints

- `GET /health`: public liveness check, no personal data.
- `GET/PUT /api/sync/content`: SyncYomi-compatible binary backup with ETags and `X-API-Token`.
- `POST /api/sync/event`: compatibility ACK, no events retained.
- `POST /mcp`: official MCP Streamable HTTP transport; requires `Authorization: Bearer <MANGA_MCP_TOKEN>`.

MCP tools are `library_stats` and `search_library`. Titles tagged with explicitly age-restricted genres are excluded from tool results. This is not a complete content-classification system.

## Connection setup

1. Host behind HTTPS and use **two distinct 32+-character secrets**, one for the Android sync API and one for MCP. Keep both secrets outside Git.
2. In the app, choose **Settings → Data & storage → Sync → SyncYomi**, enable only library/chapter sync, set server URL to the bridge host, and enter your private **sync** token.
3. Trigger Sync in the app, then verify `library_stats` reports `has_sync_data: true`. Until then, MCP correctly returns an empty library.
4. Register the HTTPS MCP endpoint in ChatGPT with secure authentication or use the private plugin package. Do not place the MCP bearer secret inside a public plugin archive.
5. In-app APK updates are independent of this bridge. Release new Android versions from signed, tested GitHub Releases using the **same signing key**, incrementing `versionCode`.

The server intentionally skips fields that may contain credentials. However, the backing binary SyncYomi backup still can contain sensitive settings; configure the app sync options to exclude app/source/private settings. Restrict server access and back up the raw file securely.

The fork retains copyright notices and upstream Apache-2.0 license. No chapter content or content-provider assets are redistributed in this bridge.

## Mobile ChatGPT via Sites

For Android ChatGPT, build a **cloud-backed ChatGPT Site** that calls the read-only JSON companion endpoints on the server side. Both require `Authorization: Bearer <MANGA_MCP_TOKEN>` supplied through protected Site configuration (never in its client UI or a plugin ZIP):

- `GET /api/assistant/stats`: counts, unread chapters and sync status.
- `GET /api/assistant/search?query=...&unread_only=false&limit=25`: filtered library title metadata, maximum 100 entries.

These endpoints use the existing MCP credential and never return chapter files or raw backups. Both deny unauthenticated requests and responses are non-cacheable. They remain empty until the phone explicitly syncs. See [MOBILE_PLUGIN_HANDOFF.md](MOBILE_PLUGIN_HANDOFF.md) for the Site plugin implementation and security checklist.
