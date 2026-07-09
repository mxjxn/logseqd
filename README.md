# logseqd

A persistent HTTP API for querying Logseq graphs via Datalog, with live incremental reindexing.

## Why this exists

Logseq's graph data lives in markdown files. The desktop app parses these into a DataScript database and lets you run Datalog queries against them — but only inside the app. If you want programmatic access to your graph (cron jobs, dashboards, bots, external tools), you're on your own.

There are existing tools, but none solve the full problem:

| Tool | What it does | What it doesn't do |
|------|-------------|-------------------|
| **nbb-logseq** | Parse a graph and run Datalog queries from Node.js CLI | No persistent server. No HTTP API. No incremental updates. Parse-then-exit — every call re-indexes the entire graph. |
| **Logseq desktop app** | Full graph editor with live queries | Desktop-only. No API. No headless operation. Can't be called from scripts or bots. |
| **Logseq DB Sync** (beta) | Sync between Logseq instances | Sync protocol, not a query API. No Datalog access. Requires the desktop app. |
| **Direct file scraping** | Read markdown files with grep/rg | No structured queries. No block hierarchy. No tag/property indexing. No joins across pages. |

**logseqd** fills the gap: a lightweight daemon that keeps your graph in a DataScript database, serves it over HTTP, and incrementally updates when files change on disk.

## What it does

- **Persistent HTTP server** — starts once, stays up. Query your graph anytime via REST.
- **Full reindex** (`POST /reindex`) — parse the entire graph into DataScript. Used on startup and when you need a clean slate.
- **Incremental single-file reindex** (`POST /reindex-file`) — reparse just one file. Retracts old blocks, upserts the page entity, transacts new blocks. Sub-millisecond for a single page.
- **inotify file watching** — paired with `inotifywait`, any file change (edit, create, delete) triggers an automatic incremental reindex. Graph stays queryable in near-real-time.
- **Datalog queries** (`POST /query`) — run any DataScript Datalog query against your graph. Same query language Logseq uses internally.
- **Search, pages, tags** — simple REST endpoints for common lookups without writing Datalog.

## Architecture

```
  .md files on disk
        │
        ▼  (inotifywait: modify/create/delete)
  POST /reindex-file {:file "SomePage.md"}
        │
        ▼
  logseqd (Clojure + DataScript + Ring)
    ├── retract old blocks for file
    ├── upsert page entity (identity-based)
    └── transact new blocks
        │
        ▼
  DataScript in-memory database
        │
        ▼
  HTTP API
    GET  /health
    GET  /search?q=keyword
    GET  /pages
    GET  /page/:title
    GET  /tags
    POST /query          (Datalog)
    POST /reindex        (full graph)
    POST /reindex-file   (single file)
```

## Setup

Requires Clojure CLI tools.

```bash
# Start a server for a graph on a chosen port
clojure -M:run /path/to/graph 8471

# Startup automatically parses the entire graph
# Then incremental reindexing keeps it current
```

### API

**Search blocks by content**
```
GET /search?q=something
```

**Get a page with all its blocks**
```
GET /page/Some%20Page
```

**Run a Datalog query**
```
POST /query
Content-Type: application/edn

{:find [?content] :where [[?b :block/content ?content]
                          [?b :block/todo "DOING"]]}
```

**Incremental reindex a single file**
```
POST /reindex-file
Content-Type: application/edn

{:file "SomePage.md"}
```

**Full graph reindex**
```
POST /reindex
```

### inotify auto-reindex

Pair with `inotify-tools` for live updates:

```bash
inotifywait -m -e modify,create,delete,move \
  --format '%f' /path/to/graph/pages /path/to/graph/journals \
  | while read FILE; do
      [ "$FILE" = *.md ] && \
      curl -s -X POST http://localhost:8471/reindex-file \
        -H "Content-Type: application/edn" \
        -d "{:file \"$FILE\"}"
    done
```

## Use with AI agents

Powers knowledge-graph queries for AI agents — cron jobs write structured data to a Logseq graph, and agents query it in real-time via the HTTP API. The incremental reindex + inotify pipeline means agents always see fresh data with zero lag.

## Tech

- **Clojure** — JVM, Ring for HTTP
- **DataScript** — in-memory Datalog database (same engine Logseq uses)
- **Custom parser** — markdown → block tree with tags, refs, properties, TODOs
- **inotify-tools** — Linux kernel file watching for zero-polling incremental updates

## License

Private — mxjxn
