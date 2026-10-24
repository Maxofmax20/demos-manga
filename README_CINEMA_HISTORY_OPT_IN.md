# Demos Manga v0.6.3 — private Cinema history sharing

This update implements an explicit, disabled-by-default device permission:
Settings > Data and storage > Sync > Share reading history with Cinema Library.

When off:
- The current private SyncYomi bridge continues sharing authorized Manga
  library titles, chapter counts, read flags and approved category scopes.
- It does not send the historical per-chapter last-read dates.
- Existing Manga library, history, progress, downloads and reading history
  in the app remain untouched.

When the owner turns this switch on:
- The next private SyncYomi backup includes BackupHistory field 104 with
  stable chapter URLs and the real original last-read timestamp.
- The existing authenticated Demos Manga bridge can compute matching
  chapter IDs, and Cinema Library Activity can display the actual timeline.
- No read-status write, deletion, tracker account sharing, extension
  installation or chapter content is authorized by this permission.

Turning this permission off stops sending new dates in future syncs.
Existing data sent to the private server may be retained until separately
cleared; this is not a retroactive deletion action.

Reader-side opt-in is NOT enabled remotely: the owner must install the
new signed Demos Manga app, turn on the setting and request a sync.
On-device E2E verification requires the owner's phone; no phone is
connected to the build VPS.
