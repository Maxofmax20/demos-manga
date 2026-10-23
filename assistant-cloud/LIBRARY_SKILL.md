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
