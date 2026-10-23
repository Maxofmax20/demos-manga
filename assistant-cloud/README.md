# Demos Manga Cloud

Owner-private ChatGPT Site with two read-only MCP tools. Backend token resides only in protected Sites configuration. The fixed backend is https://manga.demos25.me; all requests use manual redirect rejection and no automatic retries. No chapter content or raw backups are fetched. Optional explicit pairing UI creates device-scoped sync credentials; these are not MCP tools and are never embedded in the APK.

Run node --test tests/*.test.mjs, bash scripts/build.sh and node scripts/validate-artifact.mjs. Publish exact pushed source using the Sites helper. Android ChatGPT invocation must be verified separately from HTTP tests.


## Proposed v1.1.0 (NOT YET DEPLOYED)

Adds authenticated tools for paired-device IDs, chapter IDs/read flags, available/installed extensions, owner-confirmed action proposals and status checks. Action proposals never execute on the backend; the Demos Manga Android v0.2.0 client must be installed, then the owner must approve every action in the app. Extension package changes also require Android OS confirmation. The protected Site bearer remains server-side; the current published Sites instance is still v1.0.1 until the owner-authorized Sites deployment. See assistant-bridge/REMOTE_ACTIONS_HANDOFF.md for protocol and deployment instructions.
