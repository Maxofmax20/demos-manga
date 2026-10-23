# Demos Manga remote actions v0.2.0

Status: pending Android release and ChatGPT Sites plugin update. Existing v0.1.1 and currently connected ChatGPT Cloud plugin remain read-only.

## Endpoints
- GET /api/assistant/devices (owner bearer): list paired device ids.
- GET /api/assistant/chapters?manga_id=<ID>&offset=0&limit=100 (owner bearer): chapter ids, names, read flags.
- GET /api/assistant/extensions?device_id=<ID> (owner bearer): latest age-filtered installed/available extension inventory.
- POST /api/device/extensions (paired phone token): refresh extension list when the user opens the command inbox.
- POST /api/assistant/commands (owner bearer): device_id, action, args; stores pending_confirmaton, expires after 30 minutes.
- GET /api/assistant/commands/<ID> (owner bearer): retrieve status.
- GET /api/device/commands (paired phone token): list pending requests.
- POST /api/device/commands/<ID>/result (paired phone token): completed, failed or rejected.

Supported action names and arguments:
- mark_read: manga_id, read true/false, optional chapter_id.
- library_set: manga_id, favorite true/false.
- library_add: source_id, relative source_url; manga must previously be discovered on phone.
- refresh_chapters: manga_id.
- set_setting: name dark_mode or wifi_only_downloads, value boolean.
- download_chapter, open_reader: manga_id, chapter_id. Content remains only on device.
- open_screen: screen library/browse/extensions/settings. Actual destinations are app-owned.
- install_extension/uninstall_extension: package_name from existing compatible trusted repositories; no unrestricted APK URL or adult extensions.

A request NEVER executes on the server or in the background. Android owner opens Settings > Data & storage > Sync > ChatGPT cloud requests, reviews and approves/rejects. Package install/uninstall requires separate Android OS approval. The result is reported asynchronously and must not be assumed successful merely because a request was queued.

## Site plugin Work handoff
Use owner-private Demos Manga Cloud ChatGPT Site. Preserve library_stats, search_library, private Site, opt-in pairing and protected server bearer. Add MCP tools that call VPS endpoints with server-side bearer only:
1. list_paired_devices
2. list_manga_chapters(manga_id, offset, limit)
3. list_device_extensions(device_id) - available and installed extensions, subject to age/content filters.
4. request_manga_action(device_id, action, args) - require user confirmation before submitting, display pending status.
5. get_manga_action_status(command_id)
Update skills, publish Site plugin, connect in ChatGPT Android and test. Nothing can control the phone until new signed v0.2.0 APK is installed and a phone user explicitly approves each command. Never log secrets or expose raw backups, content pages, arbitrary extension sources or private settings.

Run assistant-bridge Python tests and GitHub Actions Android release tests. Sign v0.2.0 with SAME VPS keystore used for v0.1.1. Preserve user library/data. Avoid NSFW source extensions; name/genre flag checks remain best-effort and are not full content classification.
