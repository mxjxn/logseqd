# Headless Logseq Datalog Service

## Goal

Run a CLJ/CLJS service on the server that parses the Logseq graph markdown and exposes a Datalog query API over HTTP. This lets Hermes query the graph with zero noise — no git diff noise, no markdown parsing, no missed user edits.

## Architecture

```
~/mxjxn-logseq-notes/ (git repo, synced by logseq-watcher)
        │
        ▼
┌─────────────────────────────┐
│  logseq-datalog-service     │  CLJ (babashka or JVM)
│                             │
│  1. Watch git pulls         │
│  2. Parse markdown → DataScript DB
│  3. Expose HTTP API         │
│     POST /query → Datalog   │
│     GET  /health → status   │
└─────────────────────────────┘
        │
        ▼
   Hermes (curl → JSON)
```

## Components

### 1. Markdown Parser (`src/parser.clj`)

Parse Logseq markdown into structured data matching DataScript schema.

**Input:** Logseq page markdown (frontmatter + block format)

**What to extract per page:**
- `title` — from frontmatter `title:` or filename
- `tags::` — document-level tags (comma-separated page refs)
- `blocks[]` — recursive tree of blocks, each with:
  - `content` — the text content (stripped of `id::` and `tags::`)
  - `level` — indentation depth (tab count)
  - `uuid` — from `id::` property if present (auto-generated if not)
  - `tags[]` — inline hashtags
  - `properties{}` — any `key:: value` pairs
  - `page-refs[]` — `[[Page Name]]` links
  - `block-refs[]` — `((uuid))` references
  - `embeds[]` — `{{embed ((uuid))}}`
  - `children[]` — nested blocks (recursive)
  - `todo-marker` — `TODO`, `DOING`, `DONE`, or nil
  - `created-at` — block creation timestamp (from file mtime or git)
  - `source-file` — which markdown file it came from

**Sources to parse:**
- `pages/*.md` — regular pages
- `journals/YYYY_MM_DD.md` — daily journals (date as page title)

**Key parsing rules:**
- Top-level blocks start with `- ` (or `\- ` in raw markdown)
- Children are tab-indented (`\t- `)
- Properties use `key:: value` syntax (can appear on any block)
- Tags are `#word` or `#namespaced/tag`
- Page refs are `[[Name]]`
- Block refs are `((uuid))`
- Embeds are `{{embed ((uuid))}}`
- CLJS code blocks (```cljs) — skip or extract as raw text
- Frontmatter between `---` delimiters — parse as YAML

### 2. DataScript Schema (`src/schema.clj`)

```
{:block/id        {:db/unique :db.unique/identity}
 :block/uuid      {:db/unique :db.unique/identity}
 :block/content   {:db/type :db.type/string}
 :block/level     {:db/type :db.type/long}
 :block/order     {:db/type :db.type/long}   ; position among siblings
 :block/todo      {:db/type :db.type/keyword} ; :TODO :DOING :DONE :nil
 :block/created   {:db/type :db.type/instant}
 :block/source    {:db/type :db.type/string}
 :block/tags      {:db/type :db.type/ref :db/cardinality :db.cardinality/many}
 :block/props     {:db/type :db.type/ref :db.cardinality :db.cardinality/many}
 :block/children  {:db/type :db.type/ref :db/cardinality :db.cardinality/many}
 :block/page      {:db/type :db.type/ref}
 :block/refs      {:db/type :db.type/ref :db/cardinality :db.cardinality/many}  ; [[Page]]
 :block/block-refs {:db/type :db.type/ref :db/cardinality :db.cardinality/many} ; ((uuid))

 :page/title     {:db/unique :db.unique/identity}
 :page/tags      {:db/type :db.type/ref :db/cardinality :db.cardinality/many}
 :page/blocks    {:db/type :db.type/ref :db/cardinality :db.cardinality/many}
 :page/file      {:db/type :db.type/string}
 :page/journal?  {:db/type :db.type/boolean}

 :tag/name       {:db/unique :db.unique/identity}

 :property/key   {:db/type :db.type/string}
 :property/value {:db/type :db.type/string}
}
```

### 3. Indexer (`src/indexer.clj`)

- Scan all `.md` files in `pages/` and `journals/`
- Parse each file → DataScript transactions
- Track last-indexed git SHA to support incremental updates
- On re-index: diff against last SHA, only process changed files
- Expose `(reindex!)` and `(index-changed-files [changed-paths])`

**Integration with logseq-watcher:**
- After `git pull` completes, the watcher script calls the indexer
- Could be a post-pull hook or a separate HTTP trigger
- The service watches for a trigger file or listens on a local socket

### 4. HTTP API (`src/api.clj`)

Lightweight HTTP server (babashka's `http-server` or `ring`/`jetty`).

**Endpoints:**

```
POST /query
  Body: { "query": [...datalog vector...], "args": [...] }
  Response: { "results": [...rows...] }

POST /transact
  Body: { "tx-data": [...transaction data...] }
  Response: { "tx-id": ... }

GET /health
  Response: { "status": "ok", "block-count": N, "page-count": N, "last-index": "SHA" }

GET /page/<page-name>
  Response: page entity with all blocks (JSON)

GET /block/<uuid>
  Response: block entity with children (JSON)

POST /reindex
  Response: { "status": "indexed", "pages": N, "blocks": N, "duration": "ms" }
```

**Example Datalog queries:**

```clojure
;; All TODO job/backlog items
[:find (pull ?b [:block/content :page/title])
 :where
 [?b :block/todo :TODO]
 [?b :block/tags ?t]
 [?t :tag/name "job/backlog"]]

;; Everything edited in a specific journal
[:find (pull ?b [:block/content :block/tags :block/children])
 :where
 [?p :page/title "2026-07-08"]
 [?p :page/blocks ?b]]

;; All pages mentioning [[Insurance Premium Audit Request For Policy]]
[:find (pull ?p [:page/title :page/tags])
 :where
 [?b :block/page ?p]
 [?b :block/refs ?r]
 [?r :page/title "Insurance Premium Audit Request For Policy"]]

;; Recent blocks with #be-confident tag
[:find (pull ?b [:block/content :page/title])
 :where
 [?b :block/tags ?t]
 [?t :tag/name "be-confident"]]
```

### 5. Service Runner (`src/main.clj`)

- Start DataScript DB
- Run initial full index
- Start HTTP server on port (e.g., 8471)
- Expose reindex trigger (either HTTP endpoint or file watch)
- Graceful shutdown

## Runtime Choice

**Babashka** (preferred for spike):
- Fast startup, low memory (~50MB)
- Has DataScript via `babashka/pods`
- Has HTTP server via `babashka.http-server`
- No JVM warmup needed
- Good enough for a personal graph of ~1000 pages

**Full CLJ (JVM) if needed:**
- If DataScript pod doesn't work well in babashka
- If we need higher throughput or larger graphs
- More mature DataScript support

## Integration Points

### logseq-watcher hook
Add a POST to the service after successful git pull:
```bash
# In logseq-watcher.sh, after git pull succeeds:
curl -s -X POST http://localhost:8471/reindex > /dev/null
```

### Hermes integration
Hermes cron jobs and ad-hoc queries use `curl`:
```bash
# In a cron prompt or tool call:
curl -s -X POST http://localhost:8471/query \
  -H 'Content-Type: application/json' \
  -d '{"query": ["?b :block/content", "?b :block/page ?p", "?p :page/title ?t"], "args": []}'
```

### PM2 process
```json
{
  "name": "logseq-datalog",
  "script": "bb src/main.clj",
  "cwd": "/root/logseq-datalog-service",
  "watch": false,
  "restart_delay": 5000
}
```

## Deliverables

1. **Parser** — handles pages + journals, extracts all block metadata
2. **DataScript schema + indexer** — full graph in memory, incremental updates
3. **HTTP API** — query, reindex, health, page/block lookup
4. **PM2 service** — running and auto-restarting
5. **logseq-watcher integration** — reindex on git pull
6. **Test queries** — verified against actual graph data

## Risks

- **babashka DataScript pod** — may have quirks or limited API surface. Fallback: full JVM CLJ
- **Parser edge cases** — CLJS code blocks, custom CSS blocks, properties with complex values. Mitigate with real data testing
- **Large graphs** — if graph grows past 10k blocks, memory could be an issue. DataScript is designed for client-side graphs (~100k entities is fine)
- **Logseq format changes** — parser tied to current Logseq markdown format. Should be stable but worth noting

## Out of Scope (Phase 2)

- Writing back to the graph (POST /transact saves to DataScript only, not markdown)
- CLJS eval blocks (no execution engine)
- Logseq plugins
- Real-time sync (poll-based is fine)
