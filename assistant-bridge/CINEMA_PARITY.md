# Demos Manga and Cinema Library: authoritative progress mirror

Demos Manga remains the owner of source IDs, chapter IDs, read flags,
chapter additions and exact Android category memberships. Cinema Library has
no second manga database.

New owner-only endpoints:
- GET /api/assistant/library-revision: SHA-256 of SyncYomi backup,
  source timestamp and count of permitted titles, chapters and reads.
- GET /api/assistant/library-record?manga_id=ID: one stable reader copy,
  every chapter ID/read boolean, same source revision, plus real Android
  category IDs and revision if the owner granted category-read permission.

Both use the existing owner Bearer authentication and private no-store headers.
Never serve backups, chapter pages, source URLs, credentials or restricted genres.
If chapter flags are incomplete, return an error instead of fake 1:1 parity.

The preexisting owner-confirmed remote action supports mark_read with exact
manga ID/chapter ID and Boolean read flag. The paired Android phone must
approve each action. A command marked completed does not mean the phone has
uploaded a new SyncYomi backup. Cinema must wait for authoritative revision
refresh before claiming the two apps match.

No phone data or user library is mutated by installing these endpoints.
