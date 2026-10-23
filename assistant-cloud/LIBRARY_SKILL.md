---
name: manga-library
description: Read and summarize your synchronized Demos Manga library and reading progress through its private cloud tools.
---

Use only the connected library_stats and search_library tools for ordinary library requests. These are read-only and require no running desktop or local executor. Do not use SSH, shell commands or remote desktop as an administration fallback.

Check library_stats before claiming the library is current. If has_sync_data is false, clearly say the reader has not synced yet; an empty search is not evidence of an empty phone library. Direct the owner to the private Site https://demos-manga-cloud.adoredsmile128.chatgpt.site to opt in to SyncYomi pairing. Never ask them to paste a device key or server credential in chat. Pairing is an explicit browser action, not an MCP tool. Device keys expire after 90 days and can be revoked there.

search_library accepts query (up to 200 characters), unread_only (boolean), and limit (1–100; default 25). Return only permitted title metadata and chapter counts from actual results. Do not claim unmatched titles are absent when the result reached its limit.

Never request chapter pages, downloads, raw backups, settings, account credentials or explicit content. Known age-restricted genre tags are excluded server-side; filtering is best effort, not comprehensive content classification. Do not copy private library metadata to unrelated services.

If authorization or connectivity fails, explain the tool error and direct the owner to this plugin's official Connect flow. Do not invent results or claim mobile testing without an observed phone invocation.


## New commands in Site v1.1.0 (only after published)

For writes, first identify a single paired device using list_paired_devices; obtain stable manga/chapter IDs from search_library and list_manga_chapters. Always obtain explicit user consent before request_manga_action(confirmed=true). A 202/queued result is NOT proof of execution; the user must open the Android reader cloud requests inbox and approve, then get_manga_action_status must report completed. Never automatically approve or silently modify the library. Extension installation is limited to non-restricted entries in list_device_extensions and Android OS confirmation. Do not request or return raw manga pages, backups or private settings.

## Dedicated extension actions (Site v1.2.0)

The Site's MCP tool registry adds `install_extension` and `uninstall_extension` alongside `list_device_extensions`. Both tools take an exact package name reported by the paired device and require explicit user authorization (`confirmed: true`). They only queue an action; the phone owner must approve in Demos Manga > Data and storage > ChatGPT cloud requests, and Android may show an independent OS installer or uninstaller prompt. Read the command state using `get_manga_action_status`. Never claim success from a queued/pending result. Exclude restricted/adult sources and untrusted package names. Keep protected backend credentials server-side. This change does not take effect in ChatGPT until the existing private Site is republished.


## Opt-in unattended sync (v1.3.0)

Use request_device_sync for explicit user-asked synchronization of library progress plus non-restricted extension inventory. It is a separate, narrowly scoped job requiring prior opt-in by the owner on the paired Android phone under Settings > Data and storage > Allow ChatGPT to trigger sync automatically. Without opt-in, the server must reject the request. Successful request_device_sync means ONLY queued; Android WorkManager polls roughly every 15 minutes and may be delayed by the OS. Use get_device_sync_status with the returned request_id: do not call it completed until device reports completed. No per-sync approval needed after one-time opt-in. This never authorizes other actions: install/uninstall and library changes still require manual phone approval. Turning off the option revokes outstanding sync requests and background checks. Never claim immediate delivery or guaranteed background execution.
