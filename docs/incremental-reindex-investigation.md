# Incremental Single-File Reindex — Investigation & Status

## Goal

Add `index-file!` to the logseq-datalog-service so a single file change triggers an instant, incremental reindex instead of a full graph reindex. This powers inotify-based file watchers that keep both Max's and Jeeby's Logseq graphs queryable in near-real-time.

## What Was Built

### 1. `index-file!` function (indexer.clj)

Incremental reindex for a single file. Six-step pipeline:

1. **Retract existing blocks** — query all blocks where `:block/source = page-title`, then `:db/retractEntity` each via `[:block/id bid]` lookup ref
2. **Retract page entity** — query and retract any entity with `:page/title = title`
3. **Transact tag entities** — collect all inline `#tag` values from parsed blocks, ensure they exist as `:tag/name` entities
4. **Re-transact page entity** — create page with `:page/title` (unique identity), `:page/file`, `:page/journal`, `:page/tags` (lookup refs to tag entities)
5. **Create stub pages** — for any `[[]]` references in the parsed file that don't yet have page entities
6. **Transact blocks** — use `page->block-tx` to convert the parsed block tree into DataScript transaction data, with `:block/page` set to the re-transacted page entity ID

### 2. `POST /reindex-file` endpoint (api.clj)

Accepts EDN body `{:file "SomePage.md"}` (filename relative to graph dir). Calls `indexer/index-file!` and returns result as JSON.

### 3. API design

- Uses EDN for request body (consistent with `POST /query`)
- Falls back to journals/ dir if file not in pages/
- Wraps everything in try/catch, returns `{:error "msg"}` on failure

## The Bug

### Symptoms

The block transaction (step 6) fails with one of two errors depending on what's been commented out:

1. **With full pipeline (steps 1-6):** `Lookup ref should contain 2 elements: [1176]`
   - `[1176]` is the page entity ID (e.g., Snaps = 1176, ZTEST = 1183)
   - Steps 1-5 succeed — blocks are retracted, page is retracted then re-transacted
   - But the page ends up with 0 blocks (retraction worked, re-transact failed)
   - A subsequent full `/reindex` recovers everything

2. **With retraction skipped (steps 1-2 removed, 4 removed):** `Don't know how to create ISeq from: clojure.core$comp$fn__5876`
   - This is a DataScript internal error — something passes a function where a seq is expected
   - No `comp` usage in our codebase — this is from DataScript internals
   - Occurs even with a completely minimal test file: `title:: ZTEST\n\n- hello`

### What Works

- **Full reindex** (`POST /reindex`) — works perfectly, 2260+ blocks, 98+ pages, 1115+ tags
- **`page->block-tx`** — works correctly during full index (same function, same schema, same DataScript)
- **Steps 1-5 individually** — blocks retract, page retracts, tags transact, page re-transacts (verified via health check showing block count drops then page persists)
- **Paren balance** — verified opens == closes at every iteration

### What's Been Ruled Out

| Hypothesis | Test | Result |
|---|---|---|
| Clojure reader error / hidden chars | `xxd` on all lines, UTF-8 check | Clean — only standard em-dashes in comments |
| Mismatched parens | Python paren counter | Balanced at every iteration |
| Stale `.cpcache` | `rm -rf .cpcache` + rebuild | Error persists |
| Single-element lookup ref `[?p = ?e]` | Changed to `[(= ?p ?e)]` | Error persists (different error variant) |
| `d/transact!` expects vector not map | Wrapped `cond->` in `[...]` | Error persists — was a real issue but not the only one |
| Block has self-referencing page ref | Tested with minimal page (no refs, no tags, no children) | Same error |
| Page entity ID mismatch | Query confirmed correct current EID | ID matches |
| Bare number vs lookup ref for `:block/page` | Tried `[:page/title title]` instead | Untested with retraction (same pattern works in full index) |
| `d/transact!` lazy seq vs vector | Wrapped `for` in `vec` | One error changed but block transact still fails |

### Architecture Difference Between Full Index and Incremental

The key difference: **full index resets the DB** (`d/reset-conn!`), so everything is built from scratch into a clean database. **Incremental index works within an existing DB**, retracting and re-transacting.

Possible DataScript-specific issues:
- **Entity ID reuse after `:db/retractEntity`**: DataScript might not fully clean up after entity retraction, leaving ghost references that confuse subsequent transactions
- **`:db/retractEntity` cascading**: Retracting a page entity might not properly cascade to block references, leaving dangling ref values in internal indices
- **Tempid resolution across multiple `d/transact!` calls**: The `tempid-counter` is a global atom that continues incrementing. After a full index (2260+ tempids), incremental reindex starts at -2261+. These tempids should be fresh per transaction, but there might be internal state from prior transactions

### DataScript Version

`datascript/datascript {:mvn/version "1.7.3"}` — may have known issues with `:db/retractEntity` + subsequent transacts to the same entities.

## Possible Fix Directions

1. **Skip `:db/retractEntity` — use attribute-level retraction instead**
   Instead of `[:db/retractEntity eid]`, use `[:db/retract eid :page/title val]` for each attribute, or just retract the blocks and leave the page entity alone (update it in-place with new data)

2. **Single-transaction approach**
   Combine all operations (retract old blocks + transact new blocks) into a single `d/transact!` call. DataScript processes all operations atomically, which might avoid the inter-transaction state issue.

3. **Don't retract the page entity**
   Keep the existing page entity and only retract/re-transact blocks. The page metadata (file, journal, tags) rarely changes and can be updated via a separate upsert if needed.

4. **Reset and re-transact the page's blocks only**
   Instead of retracting individual blocks, retract all `:block/page` references to the page entity, then transact fresh blocks. This might be cleaner from DataScript's perspective.

5. **Upgrade DataScript or check for known bugs**
   Check if 1.7.3 has known issues with retractEntity. The DataScript repo might have fixes in newer versions.

## Files Modified

- `src/logseq_datalog/indexer.clj` — added `index-file!` function (~60 lines)
- `src/logseq_datalog/api.clj` — added `reindex-file-handler` + `POST /reindex-file` route (~14 lines)

## Remaining Work (After Bug Fix)

- [ ] Fix the DataScript transaction error in `index-file!`
- [ ] Test incremental reindex: modify page → `POST /reindex-file` → verify via `/search` or `/query`
- [ ] Set up inotify watchers for both `/root/mxjxn-logseq-notes/` and `/root/jeeby-logseq-notes/`
- [ ] Register both datalog services + watchers as PM2 processes
- [ ] Give Max a walkthrough of how it all works
