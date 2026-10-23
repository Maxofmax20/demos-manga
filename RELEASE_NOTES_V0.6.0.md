# Demos Manga v0.6.0 — Library navigation and reliable title details

- Shows your *existing* Arabic category tabs whenever the library has
  multiple categories. You can swipe or tap between shelves without
  returning to the top-level Library button.
- Uses the Demos Manga app's own manga metadata, reading history and original
  artwork loader as the first source of truth.
- Details optionally consult Jikan's public, read-only MyAnimeList metadata.
  Only **exact title or exact known alias matches** qualify for artwork
  fallback or publication-status display. No fuzzy title substitutions.
- Original cover is always loaded first; a verified MyAnimeList image is
  used only when the local/source cover fails.
- Publication status is displayed separately from reading progress. When a
  validated MAL title says "Publishing" but a source is marked "Completed",
  the detail screen can display the verified publication status without
  changing the locally stored status or the reader's own chapter progress.
- Network errors, unavailable MyAnimeList metadata and uncertain matches
  preserve the existing source data. Lookups are cached locally to avoid
  repeatedly requesting the same title.
- Does **not** reorganize categories, modify library membership, change
  read chapters, uninstall extensions, restore backups or rewrite trackers.
- Existing cloud plugin and category approvals retain their existing scope
  and device authorization; source/extension behavior is unchanged.

## Build and test disclosure

Android app identity me.demos25.manga, versionCode 9, versionName 0.6.0.
Must be signed with the existing release certificate to support in-place
upgrade from v0.5.0. Requires Android 8+.
The VPS cannot visually test the user's phone UI or its offline image cache.
Jikan reachability from the VPS is inconsistent; app-side lookups fail safe.
