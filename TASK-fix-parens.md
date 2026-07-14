# Task: Fix paren imbalance in logseqd `update-block-handler`

## File
`/root/logseq-datalog-service/src/logseq_datalog/api.clj`

## Problem
The `update-block-handler` function (starts ~line 219) has mismatched parens. The file has 501 opens and 502 closes — off by 1. Clojure reader fails: "Unmatched delimiter: )"

## How to verify
```bash
cd /root/logseq-datalog-service
clojure -e '(try (clojure.tools.reader/read (java.io.PushbackReader. (java.io.InputStreamReader. (java.io.FileInputStream. "src/logseq_datalog/api.clj")))) (catch Exception e (println (.getMessage e))))'
```
Should print nothing on success. Currently prints the error.

## Also run after fixing
```bash
pm2 restart logseq-datalog
sleep 25
curl -s http://localhost:8471/health
```

## Test the endpoint
```bash
# First get a block ID
curl -s "http://localhost:8471/block/Mnemosyne-4357"

# Test update (this will update a block and return new block-id)
curl -X POST http://localhost:8471/update-block \
  -H "Content-Type: text/plain" \
  -d '{:id "Mnemosyne-4357" :content "### What It Is On Paper"}'
```

## What the handler does
1. Accepts EDN body: `{:id "block-id" :content "new content"}`
2. Looks up block in DataScript by `:block/id`
3. Gets page title, level, order from the block entity
4. Finds the `.md` file (pages/ or journals/ based on page name pattern)
5. Reads file lines, finds the matching line by:
   - First: exact content match (indent + "- " + old content)
   - Fallback: count bullet lines up to block/order position
6. Replaces the line in-place
7. Calls `(indexer/index-file! conn graph-dir filename)` to reindex
8. Queries DataScript for the block's new ID (global counter resets on reindex)
9. Returns `{:status "ok" :block-id "New-ID" :old-block-id "Old-ID" :file "pages/foo.md"}`

## Key detail about block IDs
`block/id` format is `"PageTitle-GlobalCounter"`. The counter is a global atom in the indexer that increments per block indexed. After `index-file!` re-runs, ALL blocks on that page get new IDs (the counter keeps going up). The handler accounts for this by querying for the new ID after reindex and returning both old and new in the response. The frontend will need to update `data-block-id` attributes after save.

## IMPORTANT: Preserve the return value
The handler's return map includes `:old-block-id` and `:block-id` (new). This is critical for the frontend to update its DOM. Don't simplify the return — the frontend needs both IDs.
