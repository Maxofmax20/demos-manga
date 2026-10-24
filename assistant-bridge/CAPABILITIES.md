# Demos Manga cloud capabilities — staged milestone A

## Shipped code in cloud 1.4.0 / Android 0.5.0 code8

| Area | Implemented | Authorization | Actual-device acceptance |
|---|---|---|---|
| Existing library stats/search | Existing filtered SyncYomi metadata; preserved | Existing pairing/sync opt-in, private owner OAuth | Live 219 titles verified |
| Authoritative categories/memberships | Read, exact IDs/names/order, Default semantics, multiple memberships, indexed pagination | New Read library/category metadata scope, default off | Needs updated phone snapshot |
| Category organization | Add/remove/replace previews, approved atomic batches, reordering, history, reverse-membership previews | Separate organization scope default off; exact preview confirmation and foreground phone approval | Needs approved real preview and phone transaction |
| Concurrency/replay | Fresh DB revision, transactional journal, idempotent preview queue, expired requests rejected | Revocation cancels old queued category requests; phone re-fetches before execution | Automated tests pass; physical test pending |
| Existing chapter actions | Legacy read/unread, refresh, open/download chapter | Existing per-action phone approval | Retained; not newly certified by this task |
| Trusted extensions | Inventory, install/uninstall requests | Known permitted package, phone approval and OS installer/uninstaller approval | Retained; no package mutation tested |
| Unattended sync | request_device_sync/get_device_sync_status exposed | Existing narrow one-time phone opt-in; WorkManager timing limits | No new automatic command execution |
| Broader settings | Legacy dark-mode/Wi-Fi-download allowlist only | Existing per-action phone approval | No arbitrary settings |
| Tracking, migrations, backup restore | Not implemented in this milestone | New scopes remain off; no credentials reported | Unavailable |
| Other requested B-D features | Outstanding after milestone A acceptance | Typed/scoped APIs required | Unavailable |

No new library tabs, navigation rows, progress shelves, categories, or reading-state changes are created by deployment. Android must be upgraded in place using the existing signing identity. All 13 new category tools are snapshot/preview/queue interfaces; publication is not proof of actual phone execution.

## Cloud tools (24)

Existing/backward-compatible: library_stats, search_library, list_paired_devices, list_manga_chapters, list_device_extensions, install_extension, uninstall_extension, request_manga_action, get_manga_action_status.

Sync: request_device_sync, get_device_sync_status.

Category milestone: list_categories, get_category, list_category_manga, get_manga_categories, list_library, list_uncategorized_manga, preview_category_changes, reorder_categories, apply_category_changes, set_manga_categories, bulk_set_manga_categories, get_category_change_history, undo_category_changes.

The two set-category tools require a reviewed preview_id and confirmed=true; they do not bypass preview or phone approval. undo_category_changes produces a new preview and rejects conflicting later edits. Reorder undo requires an explicit new reorder preview.

## Primary acceptance procedure

1. Install signed Android 0.5.0 in place; do not uninstall.
2. Under existing Sync settings enable the new Read library/category metadata scope, and Organization scope for approved edits. Both default off.
3. Open ChatGPT cloud requests online to upload the authoritative metadata snapshot. This separate endpoint does not expand SyncYomi backup fields.
4. In a fresh ChatGPT Android conversation select the existing Demos Manga Cloud plugin. Invoke list_categories and paginate list_library until next_offset=null, checking revision consistency.
5. Interpret the owner's existing Arabic categories. Ask about ambiguous classifications; never create categories or change progress to improve organization.
6. Present every proposed title, original memberships, proposed memberships and the exact preview ID. Obtain approval.
7. Queue that preview, approve on the phone, then verify completed status AND the refreshed actual memberships. Queued or mocked results do not count as successful reorganization.


## Android v0.6.1 — revocable second-phone-confirmation reduction

An optional new Android setting enables local WorkManager to execute only
`category_changes` commands after the owner has approved the exact category
preview in ChatGPT. This setting defaults OFF and requires read_library and
organize_library scopes, a paired device and SyncYomi. Turning off either scope,
switching away from SyncYomi or turning off the setting immediately disables
future unattended execution on the phone. The existing atomic database revision,
expiry, command equality, durable journal and local result reporting remain.
Android polling is approximately every 15 minutes and may be delayed. No other
commands (extension installs, downloads, read flags, deletes, backup restore,
settings, arbitrary code) become automatically executable. Existing legacy
phone approval remains available.

This is a source implementation until the signed Android v0.6.1 release is
published and installed. Do not confuse a queued command with a completed one.

## Private reader History integration (24 October 2026)

- New owner-authenticated read-only GET /api/assistant/reading-history.
- Parses actual BackupHistory protobuf field 104 if supplied by Android,
  honoring existing restricted-genre filters, exact chapter IDs and
  metadata-only response rules. No raw backup or credentials exposed.
- Separately observes 0-to-1 read-flag changes when new SyncYomi backups
  arrive; observed times explicitly labeled observed_sync, not read dates.
- Private activity log 0600, bounded to 300 records. Does NOT guess historic
  reading dates from the existing 37k chapter read flags.
- Current backup contains 0 dated history records. Future opt-in reader
  updates can include local phone history for older chapter read dates.
