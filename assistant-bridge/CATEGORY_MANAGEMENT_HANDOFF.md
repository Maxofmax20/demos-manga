# Category management milestone A

Version: Android 0.5.0/code8, cloud 1.4.0. This is a staged implementation; do not claim end-to-end acceptance until the owner updates and enables the new scopes.

No library navigation or category tabs are added. No real category or reading state is changed by deployment. Android read_library and organize_library are separate opt-ins, default disabled. Foreground inbox approval remains mandatory for category commands. Existing legacy commands preserve per-action phone approval.

The Android DatabaseHandler transaction reads CategoryRepository and MangaRepository directly, derives exact existing category identities/names/order, and produces a separate metadata-only snapshot. Restricted titles are excluded. Default ID0 is represented by empty explicit memberships; it is never renamed, reordered or deleted by this milestone.

GET /api/assistant/categories and /category-library require owner bearer, a valid paired device, and uploaded phone consent. Offset pagination supports all library titles with 100 per page. Absent or revoked snapshots return an explicit error, never invented records.

POST /category-preview stores immutable before/after assignments with a device revision. add/remove/replace are distinct. /category-apply requires exact preview approval and queues at most one command per preview. Backend checks snapshot revision/scopes and TTL; Android rechecks fresh DB revision and current local scopes inside a transaction. Every batch rolls back on failure. The journal commits in the same transaction for idempotent replay after reporting interruptions. Reporting failure cannot turn a successful local commit into a failed action.

get_category_change_history reports actual command states. undo_category_changes creates a NEW preview reversing completed memberships only if the latest snapshot still matches the applied memberships. It does not undo reorders; use an explicit reorder preview. Approval is required again. No automatic queue execution.

The existing unattended sync flow also uploads the opt-in snapshot. Open Settings > Data & storage > Sync; enable the library/category metadata scope (and organization scope for edits), then open ChatGPT cloud requests online. This records scopes and categories without broadening SyncYomi backup payloads.

Acceptance requires actual Android install, new-scope consent, category snapshot, reviewed real-library preview, exact owner approval and a phone-approved transaction. Queued requests, mocked tests and backend snapshots alone are not phone acceptance.

Future milestones B-D and create/rename/delete/visibility/sort category operations are not implemented here. Never advertise them. Existing 11 legacy/sync tools remain; 13 domain-specific category tools are added for 24 total. set_manga_categories and bulk_set_manga_categories are approval aliases for a previously generated preview, not unrestricted setters.
