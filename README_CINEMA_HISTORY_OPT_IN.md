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


## Linked database refresh

With the existing separate unattended-sync consent switched on, a
WorkManager job syncs your private reader to the same server that Cinema
reads, approximately every 15 minutes (Android can delay). Leaving the
reader schedules a throttled immediate sync after storing a read event,
unless incognito is enabled. Cinema checks only the authenticated cloud
revision every 75 seconds while visible. This is an authenticated,
eventually consistent shared source of truth, not an Android shared local
SQLite file, unrestricted write access, or an always-on background socket.

The official Demos Manga release must be built with -Penable-updater;
the existing GitHub in-app update behavior is part of release validation.

## Paginated exact history

The private reading-history API supports limit and offset, exposing
bounded pages of actual reader history instead of truncating everything
at 300 dated chapters. It returns original timestamps, exact chapter
identities and pagination metadata without chapter content or credentials.


## Demos Manga 0.6.5 — first-class Cinema connection status

Settings > Sync > Cinema Library sync health displays the last locally
confirmed private upload, automatic upload permission and optional dated
chapter history sharing. An explicit Sync now action can enqueue the phone
upload. Changing history sharing now also requests a private upload,
so Cinema can detect the permission change without a second manual tap.

This is status from the phone, not a falsely declared cross-app
synchronization guarantee. Cinema still verifies its own cloud revision;
Android networking and battery management may postpone delivery.
No credentials, read flags or chapter data are written into diagnostics.
