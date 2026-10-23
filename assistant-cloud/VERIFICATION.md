# Demos Manga Cloud deployment — 23 October 2026

Site: https://demos-manga-cloud.adoredsmile128.chatgpt.site

Plugin: https://chatgpt.com/plugins/plugin_asdk_app_sites_4183822d2c4481919419deea012dd465

Plugin version 1.0.1. The existing standalone plugin is preserved. Sites confirms owner-only access: one authorized account, no groups or external visitors. The associated cloud plugin is reported already installed.

## Implementation

Two read-only tools: library_stats and search_library. They call only the existing VPS metadata API. The existing MANGA_MCP_TOKEN is held in protected Sites runtime configuration and has not been copied into source, browser assets or reports. Metadata is allowlisted to permitted titles and progress. Neither tool fetches chapter content, private settings or raw backups. Known explicit genre tags are filtered server-side; this is best effort rather than comprehensive classification.

Optional device pairing is an explicit action on the private Site, separate from MCP tools. It issues random SyncYomi credentials stored only as hashes on the server, with 90-day expiry, a three-device limit, and revocation. The APK contains no new shared token. The existing Android app and its local data were not modified.

## Verification

- Eight Node tests passed: tool discovery, identity and origin checks, validation, metadata allowlisting, empty-sync behavior, secret non-disclosure, malformed results, rejected authorization and redirects without retries.
- Eleven Python tests passed with temporary data, including device pairing, expiry, revocation, token separation, synthetic SyncYomi upload, ETag handling, metadata extraction and explicit-genre filtering.
- Actual hosted /healthz/upstream returned HTTP 200: stats successful and search successful.
- Actual live stats: zero titles and has_sync_data=false. No backup has yet synced from the Android reader. Synthetic test records were not uploaded to production.
- Anonymous Site access returned 401. Backend metadata and raw-sync endpoints return 401 without credentials.
- Sites deployment succeeded: appgdep_6adb7d04c92c819187c8e3d590a360d3, source 89856c55f893223e9e6cb968ef3d094a870b7954.
- All runtime services are cloud-hosted. SSH was used for development, not as a runtime dependency.

## Remaining device acceptance

This conversation has not loaded the new native plugin tools. No actual ChatGPT web or Android invocation has been observed in this session. Published tool discovery and successful hosted API execution do not prove phone acceptance. Start a new chat on each surface, select Demos Manga Cloud and request stats and search; authorize Connect if prompted.

For real data, open the private Site on your phone, create a device key, then configure Demos Manga Settings → Data & storage → Sync → SyncYomi with https://manga.demos25.me and that key. Enable library and chapters and trigger sync. Never paste the key into chat. Sync remains opt-in; no phone data is invented or imported without this action.

## Source and rollback

GitHub PR: https://github.com/Maxofmax20/demos-manga/pull/3 . Development remains on feat/chatgpt-sites-cloud as required by AGENTS.md. The production bridge runs that tested source. Revert the source and restart demos-manga-bridge.service to roll back backend pairing; preserve the data directory and environment file. Previous Sites versions are retained. Disconnect the cloud plugin without deleting reader data. Existing MCP and legacy sync credentials remain unchanged.
