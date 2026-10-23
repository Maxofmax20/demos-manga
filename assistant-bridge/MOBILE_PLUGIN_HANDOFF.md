# Demos Manga mobile ChatGPT plugin handoff (2026-10-23)

## Goal
Make Demos Manga's privately synchronized metadata available in ChatGPT Android and web through a cloud-backed **ChatGPT Site-hosted MCP plugin**, without requiring local desktop execution during use.

## Existing assets
- Source: https://github.com/Maxofmax20/demos-manga
- Android APK: https://github.com/Maxofmax20/demos-manga/releases/tag/v0.1.1
- VPS: vps-vnic, via Remote Desktop Commander
- HTTPS API: https://manga.demos25.me
- Existing private standalone plugin is not reliably mobile-ready: https://chatgpt.com/plugins/plugins_6adb66fd16b48191bc0fafdea42d063d
- All data/keys are managed on VPS outside Git under /home/ubuntu/.config/demos-manga and /home/ubuntu/.local/share/demos-manga.

## Cloud-hosted adapter inputs
Configure a *private owner-only* ChatGPT Site and add an MCP server exposing these **read-only** tools:
1. library_stats(): HTTP GET https://manga.demos25.me/api/assistant/stats
2. search_library(query: str = "", unread_only: bool = false, limit: int = 25): HTTP GET https://manga.demos25.me/api/assistant/search?query=<url-encoded>&unread_only=<boolean>&limit=<1..100>

The Site's **server-side** HTTP fetches must send:
`Authorization: Bearer <private MANGA_MCP_TOKEN>`
Keep the bearer in the protected Site secrets/configuration. Read the existing token securely through authorized VPS/Remote Desktop Commander. NEVER paste or print the token into chat, source, logs, browser JS, releases, frontend bundles, URLs, or plugin manifests. Never ship a new token as a substitute without updating and rotating the server-side credential safely. If a protected Site secret cannot be created programmatically, request user action through the product's secret UI.

Backend responses are JSON, with Cache-Control: no-store. 401 is expected for unauthorized calls. The API never provides chapter pages or raw backups. A successful response with has_sync_data=false is expected until the Android app has synced.

## Required behavior
- Site must authenticate the caller and allow exactly one owner; do not make public or accept arbitrary users.
- Only read metadata. Never allow remote write operations to the library. Deny access to raw /api/sync/content, all private settings and chapter images.
- Do not create any browser-side network requests with the backend credential.
- Exclude adult/explicit titles. Source genre filtering is a best-effort safeguard, not a guarantee; do not describe as comprehensive.
- Publish Site and **its associated MCP plugin** using Sites' native 'add MCP tools and publish' workflow, not a local-executor plugin.
- Install/Connect plugin under the same ChatGPT account; creation/installation requires explicit user interaction or authorization.
- Check ChatGPT Android: open Plugins > Installed/Personal, find the new Site-backed plugin, invoke `@Demos Manga`, call stats and search. Verify same results in ChatGPT web.
- If a Site cannot fetch the API due to Cloudflare security, diagnose on the authorized server. Make a narrowly scoped Cloudflare rule if needed; do not disable the WAF or expose credentials.
- Document Site URL, plugin URL, tools discovered, setup steps, and tested environments. Do not claim phone access until tested.

## Android sync note
The Android app syncs through Settings > Data & storage > Sync > SyncYomi, using server URL https://manga.demos25.me and the separate MANGA_SYNC_TOKEN, not the MCP token. It sends/receives SyncYomi protobuf via /api/sync/content with X-API-Token. User should explicitly opt in. Do not share the sync key in chat or expose full backup to the Site. The installed v0.1.1 APK still needs a simpler pairing UI in a future release.

## Troubleshooting
- Verify `https://manga.demos25.me/health` HTTP 200.
- Without bearer: `/api/assistant/stats` and `/api/assistant/search` should yield HTTP 401.
- With the protected bearer: both endpoints should yield HTTP 200; search is a list, stats has counts and has_sync_data.
- If `has_sync_data` is false, configure phone opt-in sync; it is not a ChatGPT plugin failure.
- A plugin consisting only of `.mcp.json` with a raw bearer token (our v0.1.0 standalone plugin) does not satisfy account OAuth discovery, and local-only MCP tools do not become available on mobile merely by saving the plugin.
