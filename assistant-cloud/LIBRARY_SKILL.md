---
name: manga-library
description: Read your synchronized manga library and queue owner-approved Android requests through Demos Manga Cloud.
---

Use only the connected cloud tools for ordinary requests. No desktop, SSH or local executor is required. Never use a terminal or remote desktop as a fallback for normal library actions.

The nine tools are library_stats, search_library, list_paired_devices, list_manga_chapters, list_device_extensions, install_extension, uninstall_extension, request_manga_action and get_manga_action_status.

Check library_stats before claiming the library is current. has_sync_data=false means no reader sync has arrived; it does not prove the phone library is empty. Pairing is an explicit owner action at https://demos-manga-cloud.adoredsmile128.chatgpt.site. Never ask for a device key or backend credential in chat. Do not create or revoke pairing unless explicitly requested through the official Site.

Use search_library for permitted titles, counts and stable manga IDs; use list_manga_chapters for chapter IDs/read flags. Respect pagination and never claim exhaustive results when limits are reached. List paired devices and disambiguate before targeting one.

Before any write, require explicit user authorization for that specific action and target. Only then pass confirmed=true. install_extension/uninstall_extension require an exact non-restricted package_name returned by list_device_extensions for the selected device. Do not invent IDs, accept arbitrary APK URLs or use untrusted/restricted sources.

All writes only QUEUE a request. The owner must open Demos Manga > Settings > Data & storage > Sync > ChatGPT cloud requests and approve or reject it. Android may also require a separate installer/uninstaller approval. Never approve on the owner's behalf, retry a timed-out write automatically, or claim execution from pending_confirmation. Poll get_manga_action_status with the returned command_id; only completed is a reported completion. Explain rejected, failed and expired honestly.

request_manga_action supports the server allowlist for read flags, library membership, chapter refresh/download, opening the reader or an app screen, approved settings and trusted extensions. Chapter content stays on the phone. Never request raw pages, backups, credentials or private settings, and never expose explicit content. Known adult filtering is best effort, not comprehensive classification.

On authentication or connection failure, report the tool error and use the official Connect flow. Do not invent results or claim Android verification without an observed phone invocation.


## Category organization milestone A — v1.4.0

New category tools: list_categories, get_category, list_category_manga, get_manga_categories, list_library, list_uncategorized_manga, preview_category_changes, reorder_categories, apply_category_changes, set_manga_categories, bulk_set_manga_categories, get_category_change_history, undo_category_changes. request_device_sync and get_device_sync_status are now published too.

Categories come from an explicitly authorized snapshot of the Android repositories, not inferred from backup category order. Every response is a snapshot with revision and timestamp, not proof the phone is online. If data is missing or a scope is disabled, explain the error. The owner must update to the category-capable Android release, enable the separate Read library/category metadata scope (and organization scope for writes), then open ChatGPT cloud requests online. Never enable scopes on their behalf.

For organization, list existing categories and preserve exact Unicode/Arabic names, IDs, order and visibility. Default is id "0" with no explicit memberships. Never create progress shelves, duplicate tabs, new categories or alter reading progress to organize. Do not rename or classify categories from guesswork; ask about ambiguous meanings.

Use list_library with offset/limit and follow next_offset until null. Compare revision on every page; restart if it changes. Inspect all titles and memberships before claiming full-library coverage. Restricted titles are excluded and must not be inferred absent from the phone. Use existing permitted progress tools for progress; do not invent unavailable metadata.

Build a preview with explicit add/remove/replace changes, stable manga/category IDs and expected_revision. Add/remove preserve other memberships; replace may intentionally remove them. Present every affected title with original and proposed assignments, splitting the preview into readable batches when needed. Ask for approval of the exact preview. A new preview invalidates approval of an earlier plan.

Only apply_category_changes (or the set_manga_categories/bulk_set_manga_categories approval aliases) queues an already reviewed preview_id with confirmed=true. Android then rechecks local permissions and current database revision inside an atomic transaction and requires foreground approval. Queued/pending is not success; never retry a timed-out write automatically. Poll get_manga_action_status, and after completed refresh/read the uploaded memberships to verify the actual database result. If a revision conflict occurs, refresh and preview again; do not silently reuse approval.

reorder_categories only previews the exact non-system category ordering; Default never moves. undo_category_changes generates a new reverse-membership preview for a completed change and rejects later conflicting edits. It does not directly undo or support reorder undo. Show it and obtain fresh approval before applying. get_category_change_history distinguishes previews from actual command states.

No additional category CRUD, arbitrary settings, migration, tracking, backup restore or other future capability should be claimed unless its typed tool is actually available and tested. Read and organization scopes are implemented for this milestone; later permission scopes remain disabled.


## Android v0.6.1 optional trusted category execution

If and only if the owner has installed v0.6.1 and independently enabled
"Apply ChatGPT-reviewed category changes without another phone prompt" in
Sync settings, an approved category preview can be executed by Android's
periodic worker without reopening the ChatGPT cloud requests inbox. Other
writes still require foreground phone confirmation and Android OS security
prompts remain in effect. This does not remove the need to show the exact
category preview and obtain user approval in ChatGPT before queuing it.
The worker enforces the same category-scoped consent, pending command equality,
expiry, local revision and durable transactional journal as the manual path.
A queued or running status never means completed. Disable the new phone toggle
or either category scope to revoke this behavior immediately. The live plugin
skill may need to be republished before communicating the new capability.
