# Demos Manga Cloud

Owner-private ChatGPT Site with two read-only MCP tools. Backend token resides only in protected Sites configuration. The fixed backend is https://manga.demos25.me; all requests use manual redirect rejection and no automatic retries. No chapter content or raw backups are fetched. Optional explicit pairing UI creates device-scoped sync credentials; these are not MCP tools and are never embedded in the APK.

Run node --test tests/*.test.mjs, bash scripts/build.sh and node scripts/validate-artifact.mjs. Publish exact pushed source using the Sites helper. Android ChatGPT invocation must be verified separately from HTTP tests.
