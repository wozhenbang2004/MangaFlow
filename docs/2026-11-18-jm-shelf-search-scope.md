# Adult JM shelf reconciliation and source-field search

**Checkpoint:** 2026-11-18. Source-level change set; no tests, local builds, installed screenshots, remote account calls or performance claims. The GitHub CI workflow remains the compilation/packaging gate.

## Strict profile boundaries

- Normal browsing/search runs **only** registered, enabled `BodySourceInfo.adult == false` adapters. The query dispatcher filters before sending a request; the ordinary screen is not trusted as a privacy boundary.
- Adult browsing/search runs only the explicitly supplied adult adapter list, with per-source failures. Source IDs and raw source identities remain authoritative; no guessed same-title cross-site writes.
- `LibraryStore` retains an existing shared durable list, while both shelf screens continue to project disjoint source groups. The new JM reconciler only scans or mutates canonical, encoded `jmcomic` IDs. It cannot import, delete or upload Copy/Pica/other source records.
- A JM login is independent of MangaFlow's Copy account. Logging into JM does not import, upload or remove favorites automatically.

## JM favorites

The adult shelf keeps its **existing one local shelf**, with a compact “禁漫同步” command rather than a second root shelf. Opening it reads all account favorite pages into a complete in-memory snapshot, verifies owner/session identity and compares only local JM work IDs. Remote-only and local-only differences are shown distinctly.

The user must explicitly choose each operation: import remote to local, upload local to JM, remove local, remove remote. Deletions require a second confirmation. Re-read the whole remote set and revalidate the local set and account/session before any selected mutations. JM `toggleAlbumFavorite` remains guarded by a read of the current actual favorite state; `manageFavoriteFolder` is not used for bulk push. No site deletion is implied by a local removal. A failed partial apply reports how many operations completed and discards stale preview.

No automatic scheduled writes, no passwords/Cookies in preview or audit, no cross-source title guessing, no unverified remote missing-as-deletion.

## Cross-source search

- Ordinary title searches retain their existing progressive multi-source aggregation, but cannot reach adult adapters.
- Ordinary author and label searches now use the same cross-source dispatcher and source failure/status presentation.
- Adult title/author/tag searches operate inside adult sources only and use the same compact selector.
- JM uses its native `SearchMainTag.AUTHOR` and `SearchMainTag.TAG`. Picacg resolves exact native category labels where available. Other adapters issue bounded keyword requests and filter only author/tag metadata actually returned; these **may miss matching works** and are never represented as exhaustive source-native indexes.
- A Copy tag's source-specific path is never reused as another provider's category ID. Foreign/dynamic tags use display names, and Copy maps only verified names back to Copy's native tag index.
- Source-specific and all-source progress remain separate; one remote error does not cancel sibling sources. The UI's source fan/status works for author and tag results as well as titles.

## Remaining limitations

- Device acceptance and real account API success remain unverified until an installed build is used. Generic metadata-filter searches cannot guarantee full recall when an upstream keyword search does not index authors/tags.
- JM cross-device reading progress sync is a distinct future capability; this change is **favorites only**. Folder-by-folder remote reconciliation is also not yet implemented: JM's “全部” favorite feed is the complete remote membership source.
- Adult/normal shelves currently share the underlying storage document but filter by source identity; physical file-level encryption/separation is not introduced by this change. This distinction must not be misreported.
