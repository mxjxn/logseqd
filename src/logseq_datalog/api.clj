(ns logseq-datalog.api
  (:require [datascript.core :as d]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ring.middleware.params :as params]
            [logseq-datalog.indexer :as indexer]
            [logseq-datalog.parser :as parser])
  (:import [java.io PushbackReader InputStreamReader File]
         [java.util UUID]))

;; ─── Index Dedup (mtime-based) ────────────────────────────────
;; Write handlers (append, update-block, insert-block, change-block-level)
;; modify the .md file then immediately call index-file!. The inotify watcher
;; detects that write ~0.5s later and calls /reindex-file, which would reindex
;; the same (unchanged) file — burning through block IDs and making every ID
;; in the frontend stale.
;;
;; Strategy: record the file's mtime AFTER indexing. When the inotify-triggered
;; reindex arrives, compare the current mtime to the recorded one. If they
;; match, the file hasn't been touched since the handler indexed it → skip.
;; If the mtime is newer, a genuine external edit happened → reindex.

(def ^:private indexed-mtimes (atom {}))

(defn- resolve-file-path [graph-dir filename]
  "Find the actual path for a file, checking pages/ and journals/."
  (let [graph-dir-file (java.io.File. graph-dir)
        pages-dir     (java.io.File. graph-dir-file "pages")
        journals-dir  (java.io.File. graph-dir-file "journals")
        pages-cand    (java.io.File. pages-dir filename)
        journals-cand (java.io.File. journals-dir filename)]
    (cond
      (.exists pages-cand)   pages-cand
      (.exists journals-cand) journals-cand
      :else nil)))

(defn- index-and-record! [conn graph-dir filename]
  "Index a file and record its mtime so the inotify-triggered
   reindex-file-handler can detect whether the file changed since."
  (let [stats   (indexer/index-file! conn graph-dir filename)
        path    (resolve-file-path graph-dir filename)
        mtime   (when path (.lastModified path))]
    (when mtime (swap! indexed-mtimes assoc filename mtime))
    stats))

(defn- unchanged-since-index? [graph-dir filename]
  "True if the file exists and its mtime matches the last handler-indexed
   mtime (meaning the handler already indexed this exact content)."
  (when-let [recorded-mtime (get @indexed-mtimes filename)]
    (when-let [path (resolve-file-path graph-dir filename)]
      (= recorded-mtime (.lastModified path)))))

;; ─── Helpers ──────────────────────────────────────────────────

(defn- read-edn-body [request]
  (with-open [r (PushbackReader.
                  (InputStreamReader.
                    ^java.io.InputStream (:body request)))]
    (edn/read r)))

(defn- json-resp [data & [status]]
  {:status  (or status 200)
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string data)})

;; ─── Property Helpers ─────────────────────────────────────────

(defn- fold-props
  "Given a pulled entity map, collect all :prop/<key> attributes into a nested
  \"properties\" {key value} map and drop the raw :prop/* keys and the
  *-property-keys enumeration attr. Keeps the output clean for JSON."
  [m]
  (let [prop-entries (for [[k v] m
                           :when (= "prop" (namespace k))]
                       [(name k) v])]
    (-> (into {} (remove (fn [[k _]]
                           (or (= "prop" (namespace k))
                               (contains? #{:block/property-keys
                                            :page/property-keys} k)))
                         m))
        (cond-> (seq prop-entries) (assoc :properties (into {} prop-entries))))))

(defn query-handler [conn request]
  (try
    (let [body (read-edn-body request)
          args (:args body [])
          query (-> body (dissoc :args))]
      (if (or (nil? (:find query)) (nil? (:where query)))
        (json-resp {:error "Query must include :find and :where"} 400)
        (let [results (apply d/q query @conn args)]
          (json-resp {:results (vec results)
                      :count   (count results)}))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 400))))

(defn health-handler [conn _]
  (let [blocks (or (d/q '[:find (count ?b) . :where [?b :block/content]] @conn) 0)
        pages  (or (d/q '[:find (count ?p) . :where [?p :page/title]] @conn) 0)
        tags   (or (d/q '[:find (count ?t) . :where [?t :tag/name]] @conn) 0)]
    (json-resp {:status "ok" :blocks blocks :pages pages :tags tags})))

(defn pages-handler [conn _]
  (let [results (d/q '[:find (pull ?p [*])
                       :where [?p :page/title]] @conn)
        pages (->> results
                   (map first)
                   (map (fn [p]
                          (let [page-map (-> (fold-props p)
                                            (dissoc :db/id))]
                            ;; Add file modification time if page/file exists
                            (if-let [f (:page/file page-map)]
                              (let [file (File. ^String f)]
                                (if (.exists file)
                                  (assoc page-map :page/mtime (.lastModified file))
                                  page-map))
                              page-map))))
                   vec)]
    (json-resp {:pages pages})))

(defn page-handler [conn request]
  (let [raw-title (get-in request [:params "title"])
        title (java.net.URLDecoder/decode raw-title "UTF-8")
        page-props (first
                     (d/q '[:find (pull ?p [*])
                            :in $ ?title
                            :where [?p :page/title ?title]]
                          @conn title))
        results (d/q '[:find (pull ?b [* {:block/tags   [:tag/name]}
                                         {:block/refs   [:page/title]}
                                         {:block/parent [:block/id :block/content]}])
                       :in $ ?title
                       :where [?b :block/page ?p]
                              [?p :page/title ?title]]
                     @conn title)
        blocks (->> results
                    (map first)
                    (map (fn [b] (-> (fold-props b)
                                     (dissoc :db/id :block/page :block/source))))
                    (sort-by :block/order)
                    vec)]
    (if (seq blocks)
      (json-resp (cond-> {:title title :blocks blocks}
                   (some? page-props)
                   (assoc :page (-> (fold-props (first page-props))
                                    (dissoc :db/id)))))
      (json-resp {:error "not found" :title title} 404))))

(defn tags-handler [conn _]
  (let [block-tags (d/q '[:find ?name (count ?b)
                          :where [?t :tag/name ?name]
                                 [?b :block/tags ?t]] @conn)
        page-tags  (d/q '[:find ?name (count ?p)
                          :where [?t :tag/name ?name]
                                 [?p :page/tags ?t]] @conn)
        ;; Merge counts (same tag can appear on both blocks and pages)
        counts (reduce (fn [acc [name cnt]]
                         (update acc name (fnil + 0) cnt))
                       {}
                       (concat block-tags page-tags))
        tags (vec (for [[name cnt] (sort-by (fn [[_ c]] (- c)) counts)]
                    {:name name :count cnt}))]
    (json-resp {:tags tags})))

(defn search-handler [conn request]
  (let [q (get-in request [:params "q"] "")
        ;; Search block content, but exclude journals (title matches \d{4}_\d{2}_\d{2})
        ;; and pages with private:: true
        results (d/q '[:find (pull ?b [:block/content
                                       {:block/page [:page/title]}])
                       :in $ ?search
                       :where [?b :block/content ?content]
                              [(clojure.string/includes? ?content ?search)]
                              [?b :block/page ?p]
                              [?p :page/title ?title]
                              (not [(re-matches #"(?i)^\d{4}[-_]\d{2}[-_]\d{2}(.*)?" ?title)])
                              (not [(re-matches #"(?i)^TODAY - " ?title)])
                              (not [(re-matches #"(?i)^[A-Z][a-z]{2} \d{1,2}(?:st|nd|rd|th), \d{4}" ?title)])
                              (not [?p :prop/private "true"])]
                     @conn q)
        ;; Deduplicate: group by page, take first 3 blocks per page
        by-page (reduce (fn [acc item]
                           (let [page-title (get-in item [:block/page :page/title] "unknown")
                                 content (:block/content item)]
                             (update acc page-title
                                     (fn [blocks]
                                       (if (< (count blocks) 3)
                                         (conj blocks content)
                                         blocks)))))
                         (sorted-map)
                         (map first results))
        ;; Return compact format: array of {page, blocks}
        output (vec (map (fn [[title blocks]]
                           {:page title
                            :blocks blocks})
                         by-page))]
    (json-resp {:query   q
                :results output
                :pages   (count output)
                :count   (count results)})))

;; ─── Templates ────────────────────────────────────────────────

(def ^:private templates-cache
  (atom {:data nil :timestamp 0}))

(def ^:private templates-cache-ttl-ms (* 5 60 1000))

(defn- templates-cache-valid? []
  (let [{:keys [data timestamp]} @templates-cache]
    (and (some? data)
         (pos? timestamp)
         (< (- (System/currentTimeMillis) timestamp) templates-cache-ttl-ms))))

(defn- extract-template-name
  "Pull the value after 'template::' in a block's content.
   Only matches blocks where template:: appears as a property (after bullet marker),
   not in prose. Captures only the template name — stops at whitespace, backtick, or newline."
  [content]
  (when content
    (let [cleaned (str/trim content)]
      ;; Match: optional bullet, then template:: <name>
      ;; e.g. "- template:: morning-journal" or "template:: morning-journal"
      (some-> (re-find #"^[*-]?\s*template::\s*([^\s`\n]+)" cleaned)
              second
              str/trim))))

(defn- collect-template-children
  "Given a page's blocks (sorted by :block/order) and the index of the template
  block, collect the contents of subsequent blocks at strictly higher
  :block/level, stopping when the level returns to the same or lower."
  [blocks idx]
  (let [base-level (get-in blocks [idx :block/level])]
    (loop [i   (inc idx)
           acc []]
      (if (>= i (count blocks))
        acc
        (let [blk (:block/level (nth blocks i))]
          (if (> blk base-level)
            (recur (inc i)
                   (conj acc (str "- " (:block/content (nth blocks i)))))
            acc))))))

(defn- build-templates
  "Query DataScript for all blocks whose content contains 'template::' and
   assemble each into {:name :page :children} with child blocks walked by
   :block/level + :block/order within the same page."
  [conn]
  (let [all-blocks (vec
                     (d/q '[:find (pull ?b [:block/content :block/level :block/order
                                          {:block/page [:page/title]}])
                             :where [?b :block/content]]
                           @conn))
        by-page (group-by #(get-in % [:block/page :page/title]) (mapv first all-blocks))
        templates (atom [])]
    (doseq [page-title (keys by-page)]
      (let [page-blocks (sort-by :block/order (vec (get by-page page-title)))]
        (doseq [i (range (count page-blocks))]
          (let [block (nth page-blocks i nil)]
            (when block
              (let [content (:block/content block)
                    tmpl-name (extract-template-name content)]
                (when tmpl-name
                  (let [base-level (:block/level block)
                        children (loop [j (inc i) acc []]
                                   (if (>= j (count page-blocks))
                                     acc
                                     (let [child (nth page-blocks j)]
                                       (if (> (:block/level child) base-level)
                                         (recur (inc j) (conj acc (str "- " (:block/content child))))
                                         acc))))]
                    (swap! templates conj {:name tmpl-name :page page-title :children children})))))))))
    @templates))

(defn templates-handler
  "GET /templates — list all blocks defining a 'template::' property.
   Result is cached in an atom for 5 minutes (templates-cache-ttl-ms)."
  [conn _]
  (if (templates-cache-valid?)
    (json-resp {:templates (:data @templates-cache)})
    (let [templates (build-templates conn)]
      (swap! templates-cache assoc :data templates :timestamp (System/currentTimeMillis))
      (json-resp {:templates templates}))))

(defn property-handler
  "GET /property?key=type&value=job[&scope=block|page]
  Returns entities that have :prop/<key> = <value>. If value is omitted,
  returns all entities that have the property key set (any value).
  scope defaults to \"block\"; use \"page\" for page-level properties."
  [conn request]
  (let [k     (get-in request [:params "key"])
        v     (get-in request [:params "value"])
        scope (get-in request [:params "scope"] "block")]
    (if (str/blank? k)
      (json-resp {:error "key parameter required"} 400)
      (let [attr (keyword "prop" k)]
        (if (= scope "page")
          (let [results (if v
                          (d/q '[:find (pull ?p [*])
                                 :in $ ?attr ?v
                                 :where [?p ?attr ?v] [?p :page/title]]
                               @conn attr v)
                          (d/q '[:find (pull ?p [*])
                                 :in $ ?attr
                                 :where [?p ?attr _] [?p :page/title]]
                               @conn attr))
                pages (->> results (map first)
                           (map #(-> (fold-props %) (dissoc :db/id))) vec)]
            (json-resp {:key k :value v :scope "page"
                        :results pages :count (count pages)}))
          (let [results (if v
                          (d/q '[:find (pull ?b [* {:block/page [:page/title]}
                                                   {:block/tags [:tag/name]}])
                                 :in $ ?attr ?v
                                 :where [?b ?attr ?v] [?b :block/content]]
                               @conn attr v)
                          (d/q '[:find (pull ?b [* {:block/page [:page/title]}
                                                   {:block/tags [:tag/name]}])
                                 :in $ ?attr
                                 :where [?b ?attr _] [?b :block/content]]
                               @conn attr))
                blocks (->> results (map first)
                            (map #(-> (fold-props %) (dissoc :db/id :block/source))) vec)]
            (json-resp {:key k :value v :scope "block"
                        :results blocks :count (count blocks)})))))))

(defn append-handler
  "POST /append — append a block to a page or today's journal.
  Body: EDN {:title \"Page\" :content \"text\" :level 0 :journal false}
  If journal is true, appends to today's YYYY_MM_DD.md journal.
  If the file doesn't exist, creates it with minimal frontmatter.
  Writes are sandboxed to the graph's pages/ or journals/ directory."
  [conn graph-dir request]
  (try
    (let [body     (read-edn-body request)
          title    (:title body)
          content  (:content body)
          level    (:level body 0)
          journal? (boolean (:journal body))]
      (if (str/blank? content)
        (json-resp {:error "content is required"} 400)
        (let [graph-dir-file (java.io.File. graph-dir)
              today (-> (java.time.LocalDate/now)
                        (.toString)
                        (str/replace "-" "_"))
              filename (if journal?
                         (str today ".md")
                         (str (-> (or title "")
                                  (str/replace #"[/\\:?*\"<>|]" "_"))
                              ".md"))
              target-dir (if journal? "journals" "pages")
              dir-file  (java.io.File. graph-dir-file target-dir)
              filepath  (java.io.File. dir-file filename)]
          ;; Create file with minimal frontmatter if it doesn't exist
          (when-not (.exists filepath)
            (.mkdirs (.getParentFile filepath))
            (let [fm-title (if journal? today (or title "Untitled"))]
              (spit filepath (str "title:: " fm-title "\ntags::\n\n"))))
          ;; Ensure file ends with newline before appending
          (when (and (.exists filepath) (.length filepath) > 0)
            (let [fcontent (slurp filepath)]
              (when (and (not (str/blank? fcontent))
                         (not (str/ends-with? fcontent "\n")))
                (spit filepath "\n" :append true))))
          ;; Append the block
          (let [indent (apply str (repeat level "\t"))
                block-line (str indent "- " content "\n")]
            (spit filepath block-line :append true))
          ;; Reindex this file (index-file! checks both pages/ and journals/)
          (let [stats (index-and-record! conn graph-dir filename)]
            (json-resp {:status "ok" :file (str target-dir "/" filename)
                        :stats stats})))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn create-page-handler
  "POST /create-page — create a new page with title, optional properties, and optional template.
   Body: EDN {:title \"Page Title\" :properties {\"key\" \"val\"} :template \"template-name\"}
   Creates the .md file with frontmatter, applies template if given, reindexes."
  [conn graph-dir request]
  (try
    (let [body       (read-edn-body request)
          title      (:title body)
          properties (:properties body {})
          tmpl-name  (:template body)]
      (if (str/blank? title)
        (json-resp {:error "title is required"} 400)
        (let [sanitized (str/replace title #"[/\\:?*\"<>|\n\r]" "_")
              filename  (str sanitized ".md")
              graph-dir-file (java.io.File. graph-dir)
              filepath  (java.io.File. (java.io.File. graph-dir-file "pages") filename)]
          (if (.exists filepath)
            (json-resp {:error "page already exists" :title sanitized} 409)
            ;; Build frontmatter
            (let [prop-lines (for [[k v] properties]
                               (str k ":: " v))
                  frontmatter (str "title:: " title "\n"
                                   (str/join "\n" prop-lines)
                                   (when (seq properties) "\n")
                                   "\n")
                  content    (str frontmatter)
                  _ (do (.mkdirs (.getParentFile filepath))
                       (spit filepath content))
                  ;; Apply template if requested
                  _ (when (not (str/blank? tmpl-name))
                      (let [tmpl-results (d/q '[:find (pull ?b [:block/content :block/children])
                                                :where [?b :block/name ?name]
                                                [?b :template/name ?name]]
                                              @conn tmpl-name)
                            tmpl-block  (when (seq tmpl-results) (ffirst tmpl-results))]
                        (when tmpl-block
                          (let [children (:block/children tmpl-block)
                                tmpl-lines (for [c children]
                                             (let [ct (:block/content c)]
                                               (str "\t- " ct "\n")))]
                            (when (seq tmpl-lines)
                              (spit filepath (str/join tmpl-lines) :append true))))))
                  ;; Reindex
                  stats (index-and-record! conn graph-dir filename)]
              (json-resp {:status "ok"
                          :title  sanitized
                          :file   (str "pages/" filename)
                          :stats  stats}))))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn properties-schema-handler
  "GET /properties-schema — return distinct property keys used across all pages,
   with example values for the most common ones."
  [conn _]
  (try
    (let [pages (d/q '[:find (pull ?p [*])
                        :where [?p :page/property-keys]]
                      @conn)
          key-counts (atom {})
          key-examples (atom {})]
      (doseq [[raw-page] pages]
        (let [page (fold-props raw-page)]
          (doseq [[k v] (:properties page {})]
            (swap! key-counts update k (fnil inc 0))
            (when (or (not (get @key-examples k))
                      (< (count (str (get @key-examples k))) (count (str v))))
              (swap! key-examples assoc k v)))))
      (let [sorted-keys (->> @key-counts
                             (sort-by val)
                             reverse
                             (mapv first)
                             (take 15))]
        (json-resp {:properties (for [k sorted-keys]
                                  {:key k
                                   :count (@key-counts k)
                                   :example (@key-examples k)})})))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn- indexed-block-line
  "A write uses its indexed source position, never content matching or sibling
   order. Refuse if the file changed after indexing."
  [lines filepath block]
  (let [idx (:block/line block)]
    (when (and (integer? idx) (<= 0 idx) (< idx (count lines))
               (= (:block/source-mtime block) (.lastModified ^File filepath))
               (= (:block/source-line block) (nth lines idx)))
      idx)))

(defn- existing-property-lines [lines start end]
  (second
    (reduce (fn [[fences props] [i line]]
              [(+ fences (count (re-seq #"```" line)))
               (if (and (pos? i) (even? fences)
                        (parser/parse-property-line (str/trim line)))
                 (conj props line) props)])
            [0 []] (map-indexed vector (subvec lines start end)))))

(defn update-block-handler
  "POST /update-block — replace the content of an existing block.
   Body: EDN {:id \"block-id\" :content \"new content\"}
   block-id format: \"PageTitle-Counter\" (e.g. \"MyPage-1\")
   Looks up block in DataScript by id to get page title, order, level,
   then finds the line in the .md file by counting bullet positions."
  [conn graph-dir request]
  (try
    (let [body    (read-edn-body request)
          block-id (:id body)
          content  (:content body)]
      (cond
        (str/blank? block-id)
        (json-resp {:error "id is required"} 400)

        (str/blank? content)
        (json-resp {:error "content is required"} 400)

        :else
        (let [;; Look up block in DataScript
              result (d/q '[:find (pull ?b [:block/content
                                             :block/todo
                                             :block/line :block/source-line :block/source-mtime
                                             :block/end-line :block/subtree-end-line
                                             :block/level
                                             :block/order
                                             {:block/page [:page/title]}])
                           :in $ ?bid
                           :where [?b :block/id ?bid]]
                         @conn block-id)]
          (if-let [block (ffirst result)]
            (let [page-title (-> block :block/page :page/title)
                  level      (:block/level block)
                  order      (:block/order block)
                  old-content (:block/content block)]
              (if (str/blank? page-title)
                (json-resp {:error "block has no associated page"} 404)
                ;; Determine file path
                (let [graph-dir-file (java.io.File. graph-dir)
                      is-journal?   (re-matches #"\d{4}_\d{2}_\d{2}" page-title)
                      target-dir    (if is-journal? "journals" "pages")
                      filename      (str (str/replace page-title #"[\"/\\:?*<>|]" "_") ".md")
                      filepath      (java.io.File. (java.io.File. graph-dir-file target-dir) filename)]
                  (if-not (.exists filepath)
                    (json-resp {:error "source file not found"
                                :file (str target-dir "/" filename)} 404)
                    ;; Find and replace the block line
                    (let [lines      (str/split-lines (slurp filepath))
                          indent      (apply str (repeat level "\t"))
                          idx         (indexed-block-line lines filepath block)]
                      (if (nil? idx)
                        (json-resp {:error "could not locate block in file"
                                    :block-id block-id :order order :level level
                                    :content-preview (str/trim (or old-content ""))} 404)
                        (let [content (if (and (:block/todo block)
                                                     (not (re-find #"^(TODO|DOING|DONE|LATER|NOW|CANCEL)\s" content)))
                                              (str (:block/todo block) " " content) content)
                              content-parts (str/split-lines content)
                              ;; First line gets the bullet marker; continuations get
                              ;; an extra indent (no bullet) to survive parser round-trips.
                              continuation-indent (str indent "\t")
                              new-lines-block (if (<= (count content-parts) 1)
                                                 [(str indent "- " content)]
                                                 (cons (str indent "- " (first content-parts))
                                                       (map #(str continuation-indent %) (rest content-parts))))
                              ;; Also consume any old continuation lines that followed
                              ;; the original block (non-blank, non-bullet lines)
                              skip-old (:block/end-line block)
                              ;; Keep existing block properties (including UUIDs) when
                              ;; replacing content. Continuations are replaced, not kept.
                              property-lines (existing-property-lines lines idx skip-old)
                              new-lines-block (concat new-lines-block property-lines)
                              new-lines (vec (concat (subvec lines 0 idx)
                                                      new-lines-block
                                                      (subvec lines skip-old)))]
                          (spit filepath (str/join "\n" new-lines))
                          (index-and-record! conn graph-dir filename)
                          ;; block-id counter increments on reindex, so fetch the new id
                          (let [new-result (d/q '[:find (pull ?b [:block/id])
                                               :in $ ?pt ?line
                                               :where [?b :block/id ?bid]
                                                      [?b :block/line ?line]
                                                      [?p :page/title ?pt]
                                                      [?b :block/page ?p]]
                                             @conn page-title idx)
                                new-bid (-> new-result ffirst :block/id)]
                            (json-resp {:status "ok"
                                        :block-id new-bid
                                        :old-block-id block-id
                                        :page page-title
                                        :order order
                                        :file (str target-dir "/" filename)})))))))))
            (json-resp {:error "block not found" :block-id block-id} 404)))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn insert-block-handler
  "POST /insert-block — insert a new block after an existing block.
   Body: EDN {:page \"Page Title\" :content \"new block text\" :level 1 :after-block-id \"Page-7\"}
   :after-block-id, :page, and :content are all required."
  [conn graph-dir request]
  (try
    (let [body         (read-edn-body request)
          after-id     (:after-block-id body)
          page         (:page body)
          content      (:content body)
          level        (:level body 1)]
      (cond
        (str/blank? after-id)
        (json-resp {:error "after-block-id is required"} 400)

        (str/blank? page)
        (json-resp {:error "page is required"} 400)

        (str/blank? content)
        (json-resp {:error "content is required"} 400)

        :else
        (let [result (d/q '[:find (pull ?b [:block/content
                                             :block/line :block/source-line :block/source-mtime
                                             :block/end-line :block/subtree-end-line
                                             :block/level
                                             :block/order
                                             {:block/page [:page/title]}])
                           :in $ ?bid
                           :where [?b :block/id ?bid]]
                         @conn after-id)]
          (if-let [block (ffirst result)]
            (let [page-title  (-> block :block/page :page/title)
                  block-level (:block/level block)
                  order       (:block/order block)
                  old-content (:block/content block)]
              (if (str/blank? page-title)
                (json-resp {:error "block has no associated page"} 404)
                (let [graph-dir-file (java.io.File. graph-dir)
                      is-journal?   (re-matches #"\d{4}_\d{2}_\d{2}" page-title)
                      target-dir    (if is-journal? "journals" "pages")
                      filename      (str (str/replace page-title #"[\"/\\:?*<>|]" "_") ".md")
                      filepath      (java.io.File. (java.io.File. graph-dir-file target-dir) filename)]
                  (if-not (.exists filepath)
                    (json-resp {:error "source file not found"
                                :file (str target-dir "/" filename)} 404)
                    (let [lines      (str/split-lines (slurp filepath))
                          idx        (indexed-block-line lines filepath block)]
                      (if (nil? idx)
                        (json-resp {:error "could not locate block in file"
                                    :block-id after-id :order order :level block-level} 404)
                        (let [insert-pos (:block/subtree-end-line block)
                              new-indent (apply str (repeat level "\t"))
                              new-line   (str new-indent "- " content)
                              new-lines  (vec (concat (subvec lines 0 insert-pos)
                                                      [new-line]
                                                      (subvec lines insert-pos)))]
                          (spit filepath (str/join "\n" new-lines))
                          (index-and-record! conn graph-dir filename)
                          (let [new-result (d/q '[:find (pull ?b [:block/id])
                                                 :in $ ?pt ?line
                                                 :where [?b :block/id ?bid]
                                                        [?b :block/line ?line]
                                                        [?p :page/title ?pt]
                                                        [?b :block/page ?p]]
                                                @conn page-title insert-pos)
                                new-bid (-> new-result ffirst :block/id)]
                            (json-resp {:status "ok"
                                        :block-id new-bid
                                        :after-block-id after-id
                                        :page page-title
                                        :file (str target-dir "/" filename)})))))))))
            (json-resp {:error "block not found" :block-id after-id} 404)))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn delete-block-handler
  "POST /delete-block — delete a block from the markdown file.
   Body: EDN {:block-id \"Page-7\" :inherit-children true}
   :inherit-children (optional, default true): when true, children are outdented
   one level so they become siblings of the deleted block's parent. When false,
   the entire subtree (block + all descendants) is removed.
   Returns the block-id and whether it had children."
  [conn graph-dir request]
  (try
    (let [body      (read-edn-body request)
          block-id  (:block-id body)
          inherit?  (if (nil? (:inherit-children body)) true (:inherit-children body))]
      (cond
        (str/blank? block-id)
        (json-resp {:error "block-id is required"} 400)

        :else
        (let [result (d/q '[:find (pull ?b [:block/content
                                             :block/line :block/source-line :block/source-mtime
                                             :block/end-line :block/subtree-end-line
                                            :block/level
                                            :block/order
                                            {:block/page [:page/title]}])
                           :in $ ?bid
                           :where [?b :block/id ?bid]]
                         @conn block-id)]
          (if-let [block (ffirst result)]
            (let [page-title  (-> block :block/page :page/title)
                  cur-level   (:block/level block)
                  order       (:block/order block)
                  old-content (:block/content block)]
              (if (str/blank? page-title)
                (json-resp {:error "block has no associated page"} 404)
                (let [graph-dir-file (java.io.File. graph-dir)
                      is-journal?   (re-matches #"\d{4}_\d{2}_\d{2}" page-title)
                      target-dir    (if is-journal? "journals" "pages")
                      filename      (str (str/replace page-title #"[\"/\\:?*<>|]" "_") ".md")
                      filepath      (java.io.File. (java.io.File. graph-dir-file target-dir) filename)]
                  (if-not (.exists filepath)
                    (json-resp {:error "source file not found"
                                :file (str target-dir "/" filename)} 404)
                    (let [lines (str/split-lines (slurp filepath))
                          idx   (indexed-block-line lines filepath block)]
                      (if (nil? idx)
                        (json-resp {:error "could not locate block in file"
                                    :block-id block-id :order order :level cur-level} 404)
                        (let [end-idx       (:block/subtree-end-line block)
                              has-children? (> end-idx (:block/end-line block))]
                          (if inherit?
                            ;; Inherit mode: remove the target block line, outdent children by 1
                            (let [child-lines    (subvec lines (:block/end-line block) end-idx)
                                  outdented      (mapv (fn [line]
                                                         (let [line-level (count (take-while #(= % \tab) line))
                                                               rest-part   (subs line line-level)]
                                                           ;; blank lines — leave alone
                                                           (if (str/blank? line)
                                                             line
                                                             ;; outdent by 1 level (min 0)
                                                             (let [new-lvl (max 0 (dec line-level))]
                                                               (str (apply str (repeat new-lvl \tab)) rest-part)))))
                                                       child-lines)
                                  new-lines      (vec (concat (subvec lines 0 idx)
                                                              outdented
                                                              (subvec lines end-idx)))]
                              (spit filepath (str/join "\n" new-lines))
                              (index-and-record! conn graph-dir filename)
                              (json-resp {:status         "ok"
                                          :block-id       block-id
                                          :page           page-title
                                          :had-children?  has-children?
                                          :mode           "inherit"
                                          :file           (str target-dir "/" filename)}))
                            ;; Delete-all mode: remove the entire subtree
                            (let [new-lines (vec (concat (subvec lines 0 idx)
                                                          (subvec lines end-idx)))]
                              (spit filepath (str/join "\n" new-lines))
                              (index-and-record! conn graph-dir filename)
                              (json-resp {:status         "ok"
                                          :block-id       block-id
                                          :page           page-title
                                          :had-children?  has-children?
                                          :mode           "delete-all"
                                          :file           (str target-dir "/" filename)}))))))))))
            (json-resp {:error "block not found" :block-id block-id} 404)))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn change-block-level-handler
  "POST /change-block-level — change a block's indentation level in the markdown file,
   moving the entire subtree (children included).
   Body: EDN {:block-id \"Page-7\" :new-level 2}
   When indenting (new-level > current-level): adds tabs to block + all children.
   When outdenting (new-level < current-level): removes tabs, clamping children to new-level+1."
  [conn graph-dir request]
  (try
    (let [body      (read-edn-body request)
          block-id  (:block-id body)
          new-level (:new-level body)]
      (cond
        (str/blank? block-id)
        (json-resp {:error "block-id is required"} 400)

        (nil? new-level)
        (json-resp {:error "new-level is required"} 400)

        (< new-level 0)
        (json-resp {:error "new-level must be >= 0"} 400)

        :else
        (let [result (d/q '[:find (pull ?b [:block/content
                                             :block/line :block/source-line :block/source-mtime
                                             :block/end-line :block/subtree-end-line
                                            :block/level
                                            :block/order
                                            {:block/page [:page/title]}])
                           :in $ ?bid
                           :where [?b :block/id ?bid]]
                         @conn block-id)]
          (if-let [block (ffirst result)]
            (let [page-title  (-> block :block/page :page/title)
                  cur-level   (:block/level block)
                  order       (:block/order block)
                  old-content (:block/content block)]
              (if (str/blank? page-title)
                (json-resp {:error "block has no associated page"} 404)
                (let [graph-dir-file (java.io.File. graph-dir)
                      is-journal?   (re-matches #"\d{4}_\d{2}_\d{2}" page-title)
                      target-dir    (if is-journal? "journals" "pages")
                      filename      (str (str/replace page-title #"[\"/\\:?*<>|]" "_") ".md")
                      filepath      (java.io.File. (java.io.File. graph-dir-file target-dir) filename)]
                  (if-not (.exists filepath)
                    (json-resp {:error "source file not found"
                                :file (str target-dir "/" filename)} 404)
                    (let [lines (str/split-lines (slurp filepath))
                          idx   (indexed-block-line lines filepath block)]
                      (if (nil? idx)
                        (json-resp {:error "could not locate block in file"
                                    :block-id block-id :order order :level cur-level} 404)
                        (let [end-idx (:block/subtree-end-line block)
                              delta   (- new-level cur-level)
                              subtree (subvec lines idx end-idx)
                              changed-sub (mapv (fn [line]
                                                  (let [line-level (count (take-while #(= % \tab) line))
                                                        has-bullet (re-find #"^\t*- " line)
                                                        rest-part  (subs line line-level)]
                                                    (cond
                                                      ;; no bullet and not blank — property/continuation line,
                                                      ;; stays with its block (delta applies relative to parent)
                                                      (and (zero? line-level) (not has-bullet) (not (str/blank? line)))
                                                      (str (apply str (repeat new-level \tab)) rest-part)
                                                      ;; blank line — leave alone
                                                      (str/blank? line) line
                                                      ;; the parent block itself — set to new level
                                                      (= line-level cur-level)
                                                      (str (apply str (repeat new-level \tab)) rest-part)
                                                      ;; child block — adjust by delta
                                                      :else
                                                      (let [new-lvl (max (+ new-level 1)
                                                                        (+ line-level delta))]
                                                        (str (apply str (repeat new-lvl \tab)) rest-part)))))
                                                subtree)
                              new-lines (vec (concat (subvec lines 0 idx)
                                                      changed-sub
                                                      (subvec lines end-idx)))]
                          (spit filepath (str/join "\n" new-lines))
                          (index-and-record! conn graph-dir filename)
                          (json-resp {:status    "ok"
                                      :block-id  block-id
                                      :new-level new-level
                                      :page      page-title
                                      :file      (str target-dir "/" filename)}))))))))
            (json-resp {:error "block not found" :block-id block-id} 404)))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn reindex-handler [conn graph-dir _]
  (try
    (let [stats (indexer/index-graph! conn graph-dir)]
      (json-resp {:status "ok" :stats stats}))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn reindex-file-handler [conn graph-dir request]
  (try
    (let [body     (read-edn-body request)
          filename (:file body)]
      (cond
        (str/blank? filename)
        (json-resp {:error "Request body must include :file"} 400)

        ;; Skip if a write handler already indexed this exact file content
        ;; (matching mtime = file hasn't changed since the handler wrote+indexed it).
        ;; External edits produce a newer mtime and reindex normally.
        (unchanged-since-index? graph-dir filename)
        (json-resp {:status "skipped" :reason "already indexed at current mtime"})

        :else
        (let [stats (indexer/index-file! conn graph-dir filename)]
          (json-resp {:status "ok" :stats stats}))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn set-page-property-handler
  "POST /set-page-property — set or remove a page-level property.
   Body: EDN {:title \"Page\" :key \"public\" :value \"true\"} or {:remove true}.
   Handles both property forms:
   - bare Logseq form:   public:: true   (leading property zone, no frontmatter)
   - YAML frontmatter:   public: true    (inside --- ... --- block)
   Removing a missing property is a no-op. Reindexes + records mtime."
  [conn graph-dir request]
  (try
    (let [body    (read-edn-body request)
          title   (:title body)
          pkey    (:key body)
          value   (:value body)
          remove? (boolean (:remove body))]
      (if (or (str/blank? title) (str/blank? pkey))
        (json-resp {:error "title and key are required"} 400)
        (let [filename (str (str/replace title #"[/\\:?*\"<>|\n\r]" "_") ".md")
              path     (resolve-file-path graph-dir filename)]
          (if (nil? path)
            (json-resp {:error "page not found" :title title} 404)
            (let [lines   (str/split-lines (slurp path))
                  n       (count lines)
                  write!  (fn [new-lines] (spit path (str (str/join "\n" new-lines) "\n")))
                  fm-end  (when (and (seq lines) (= (str/trim (first lines)) "---"))
                            (loop [i 1]
                              (cond
                                (>= i n) nil
                                (= (str/trim (nth lines i)) "---") i
                                :else (recur (inc i)))))
                  bare-re (re-pattern (str "^" (java.util.regex.Pattern/quote pkey) "::\\s*.*$"))
                  yaml-re (re-pattern (str "^\\s*" (java.util.regex.Pattern/quote pkey) ":\\s*.*$"))
                  apply!  (fn []
                            (if fm-end
                              (let [idx (loop [i 1]
                                          (cond
                                            (>= i fm-end) nil
                                            (re-find yaml-re (nth lines i)) i
                                            :else (recur (inc i))))]
                                (cond
                                  (and remove? idx)
                                  (write! (concat (subvec lines 0 idx) (subvec lines (inc idx))))

                                  (and (not remove?) idx)
                                  (write! (concat (subvec lines 0 idx)
                                                  [(str pkey ": " value)]
                                                  (subvec lines (inc idx))))

                                  (not remove?)
                                  (write! (concat [(first lines)]
                                                  [(str pkey ": " value)]
                                                  (rest lines)))

                                  :else nil))
                              (let [zcount (count (take-while #(not (re-find #"^\t*- " %)) lines))
                                    idx    (loop [i 0]
                                              (cond
                                                (>= i zcount) nil
                                                (re-find bare-re (nth lines i)) i
                                                :else (recur (inc i))))]
                                (cond
                                  (and remove? idx)
                                  (write! (concat (subvec lines 0 idx) (subvec lines (inc idx))))

                                  (and (not remove?) idx)
                                  (write! (concat (subvec lines 0 idx)
                                                  [(str pkey ":: " value)]
                                                  (subvec lines (inc idx))))

                                  (not remove?)
                                  (write! (concat (subvec lines 0 zcount)
                                                  [(str pkey ":: " value)]
                                                  (subvec lines zcount)))

                                  :else nil))))]
              (apply!)
              (json-resp {:status "ok" :title title :key pkey
                          :action (if remove? "removed" "set")
                          :stats (index-and-record! conn graph-dir filename)}))))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn block-handler [conn request]
  (let [raw-uuid (get-in request [:params "uuid"])]
    (if (str/blank? raw-uuid)
      (json-resp {:error "uuid parameter is required"} 400)
      (let [uuid   (java.net.URLDecoder/decode raw-uuid "UTF-8")
            result (d/q '[:find (pull ?b [:block/uuid :block/content :block/level :block/order
                                           :block/todo :block/block-refs :block/embeds
                                           {:block/tags   [:tag/name]}
                                           {:block/refs   [:page/title]}
                                           {:block/page   [:page/title]}
                                           {:block/parent [:block/id :block/content]}])
                           :in $ ?uuid
                           :where [?b :block/uuid ?uuid]]
                         @conn uuid)]
        (if-let [block (ffirst result)]
          (json-resp block)
          (json-resp {:error "not found" :uuid uuid} 404))))))

(defn block-refs-handler [conn request]
  (let [raw-uuid (get-in request [:params "uuid"])]
    (if (str/blank? raw-uuid)
      (json-resp {:error "uuid parameter is required"} 400)
      (let [uuid    (java.net.URLDecoder/decode raw-uuid "UTF-8")
            results (d/q '[:find (pull ?b [:block/uuid :block/content :block/level :block/order
                                            {:block/page [:page/title]}])
                            :in $ ?uuid
                            :where [?b :block/block-refs ?uuid]]
                          @conn uuid)]
        (json-resp {:uuid          uuid
                    :referenced-by (vec (map first results))
                    :count         (count results)})))))

;; ─── Router ───────────────────────────────────────────────────

;; ─── Push Subscriptions ────────────────────────────────────

(defn- push-subscriptions-file [graph-dir]
  (File. graph-dir ".push-subscriptions.json"))

(defn- read-push-subscriptions [graph-dir]
  (let [f (push-subscriptions-file graph-dir)]
    (if (.exists f)
      (json/parse-string (slurp f) keyword)
      [])))

(defn- write-push-subscriptions! [graph-dir subs]
  (let [f (push-subscriptions-file graph-dir)]
    (.mkdirs (.getParentFile f))
    (spit f (json/generate-string subs))))

(defn push-subscribe-handler [graph-dir request]
  (try
    (let [body (json/parse-string (slurp (:body request)) keyword)
          sub  (select-keys body [:endpoint :keys :auth])
          existing (read-push-subscriptions graph-dir)
          ;; replace if same endpoint exists, otherwise add
          filtered (remove #(= (:endpoint %) (:endpoint sub)) existing)
          updated (conj filtered sub)]
      (write-push-subscriptions! graph-dir updated)
      (json-resp {:ok true :count (count updated)}))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 400))))

(defn push-unsubscribe-handler [graph-dir request]
  (try
    (let [body (json/parse-string (slurp (:body request)) keyword)
          endpoint (:endpoint body)
          existing (read-push-subscriptions graph-dir)
          filtered (remove #(= (:endpoint %) endpoint) existing)]
      (write-push-subscriptions! graph-dir filtered)
      (json-resp {:ok true :count (count filtered)}))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 400))))

;; ─── Image Upload ────────────────────────────────────────────

(defn upload-handler
  "POST /upload — accept image upload (raw body), save to graph assets/ dir.
  Uses X-Filename header for original name, Content-Type for extension fallback.
  Returns JSON {:path \"../assets/filename.ext\" :url \"/api/assets/filename.ext\"}"
  [graph-dir request]
  (try
    (let [body      (:body request)
          headers   (:headers request)
          raw-name  (or (get headers "x-filename") "upload")
          filename  (str (UUID/randomUUID) "-"
                         (-> raw-name (str/replace #"[^\w.\-]" "_")))
          assets-dir (java.io.File. graph-dir "assets")
          _          (.mkdirs assets-dir)
          dest       (java.io.File. assets-dir filename)]
      (clojure.java.io/copy body dest)
      (json-resp {:path  (str "../assets/" filename)
                  :url   (str "/api/assets/" filename)
                  :name  filename}))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

;; ─── Asset Serving ───────────────────────────────────────────

(defn asset-handler
  "GET /assets/:filename — serve a file from the graph's assets/ directory."
  [graph-dir request uri]
  (try
    (let [filename  (subs uri 8) ; strip "/assets/"
          assets-dir (java.io.File. graph-dir "assets")
          file       (java.io.File. assets-dir filename)
          ;; Security: ensure resolved path is inside assets/
          canonical-base (.getCanonicalPath assets-dir)
          canonical-file (.getCanonicalPath file)]
      (if (and (.exists file)
               (.isFile file)
               (str/starts-with? canonical-file canonical-base))
        (let [ext      (or (some-> (re-find #"\.(\w+)$" filename) second) "")
              mime     (case (str/lower-case ext)
                         "png"  "image/png"
                         "jpg"  "image/jpeg"
                         "jpeg" "image/jpeg"
                         "gif"  "image/gif"
                         "webp" "image/webp"
                         "svg"  "image/svg+xml"
                         "pdf"  "application/pdf"
                         "application/octet-stream")
              bytes    (clojure.java.io/input-stream file)]
          {:status  200
           :headers {"Content-Type" mime
                     "Cache-Control" "public, max-age=86400"}
           :body    bytes})
        {:status  404
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string {:error "not found"})}))
    (catch Exception e
      {:status  500
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error (.getMessage e)})})))

;; ─── Library & Graph views ────────────────────────────────────

(defn- excluded-title?
  "Titles hidden from library/graph: journals, cron/system pages, Logseq date pages."
  [title]
  (boolean
   (or (re-matches #"(?i)^\d{4}[-_]\d{2}[-_]\d{2}.*" (str title))
       (re-matches #"(?i)^TODAY - .*" (str title))
       (re-matches #"(?i)^[A-Z][a-z]{2} \d{1,2}(?:st|nd|rd|th), \d{4}.*" (str title)))))

(defn- private-titles
  "Set of page titles with private:: true."
  [conn]
  (->> (d/q '[:find ?t
              :where [?p :page/title ?t]
                     [?p :prop/private "true"]] @conn)
       (map first)
       set))

(defn library-handler
  "GET /library - page cards: title, mtime, first-content snippet, top refs."
  [conn _]
  (let [private (private-titles conn)
        pages   (->> (d/q '[:find (pull ?p [*]) :where [?p :page/title]] @conn)
                     (map first)
                     (map fold-props))
        page-meta (into {} (map (fn [p] {(:page/title p) p}) pages))
        blocks  (->> (d/q '[:find (pull ?b [:block/content :block/order
                                            {:block/page [:page/title]}
                                            {:block/refs  [:page/title]}])
                            :where [?b :block/page]] @conn)
                     (map first))
        by-page (group-by #(get-in % [:block/page :page/title]) blocks)
        cards   (->>
                  (for [title (keys by-page)
                        :when (and (not (excluded-title? title))
                                   (not (private title)))]
                    (let [blks   (sort-by (fn [b] (or (:block/order b) 0)) (get by-page title))
                          body   (remove (fn [b] (re-matches #"(?i)^[\w-]+::.*" (str (:block/content b)))) blks)
                          firstb (some-> (first body) :block/content str/trim)
                          snippet (if firstb
                                    (subs firstb 0 (min 160 (count firstb)))
                                    "")
                          ref-freq (frequencies
                                    (mapcat (fn [b] (map :page/title (:block/refs b))) blks))
                          tags   (->> ref-freq
                                      (sort-by (fn [e] (- (val e))))
                                      (map key)
                                      (remove (fn [t] (= t title)))
                                      (take 3))
                          meta   (get page-meta title)
                          mtime  (when-let [f (:page/file meta)]
                                   (let [file (java.io.File. ^String f)]
                                     (when (.exists file) (.lastModified file))))]
                      {:title title :mtime mtime :snippet snippet :tags tags}))
                  (filter :title)
                  vec)]
    (json-resp {:pages cards :count (count cards)})))

(defn graph-handler
  "GET /graph - force-graph edge list. Nodes: pages with >=2 links to other
   visible pages. Excludes journals/system/private (same rules as library)."
  [conn _]
  (let [private (private-titles conn)
        visible? (fn [t] (and (not (excluded-title? t)) (not (private t))))
        edges  (->> (d/q '[:find ?pt ?rt
                           :where [?b :block/page ?p]
                                  [?b :block/refs ?r]
                                  [?p :page/title ?pt]
                                  [?r :page/title ?rt]] @conn)
                    (map (fn [[a b]] (if (neg? (compare a b)) [a b] [b a])))
                    set
                    (filter (fn [[a b]] (and (not= a b) (visible? a) (visible? b))))
                    set)
        degree (reduce (fn [acc [a b]]
                         (-> acc (update a (fnil inc 0)) (update b (fnil inc 0))))
                       {} edges)
        node-titles (->> degree (filter (fn [[_ d]] (>= d 2))) (map key) sort)
        node-set (set node-titles)
        nodes  (map (fn [t] {:title t :degree (get degree t)}) node-titles)
        final-edges (filter (fn [[a b]] (and (node-set a) (node-set b))) edges)]
    (json-resp {:nodes nodes
                :edges (vec final-edges)
                :node-count (count nodes)
                :edge-count (count final-edges)})))

(defn make-app [conn graph-dir]
  (params/wrap-params
    (fn [request]
      (let [method (:request-method request)
            uri    (:uri request)]
        (cond
          (and (= method :post) (= uri "/query"))
          (query-handler conn request)

          (and (= method :get) (= uri "/health"))
          (health-handler conn request)

          (and (= method :get) (= uri "/pages"))
          (pages-handler conn request)

          (and (= method :get) (= uri "/library"))
          (library-handler conn request)

          (and (= method :get) (= uri "/graph"))
          (graph-handler conn request)

          (and (= method :get) (str/starts-with? uri "/page/"))
          (page-handler conn (assoc-in request [:params "title"] (subs uri 6)))

          (and (= method :get) (= uri "/tags"))
          (tags-handler conn request)

          (and (= method :get) (= uri "/search"))
          (search-handler conn request)

          (and (= method :get) (= uri "/property"))
          (property-handler conn request)

          (and (= method :get) (= uri "/templates"))
          (templates-handler conn request)

          (and (= method :get) (= uri "/properties-schema"))
          (properties-schema-handler conn request)

          (and (= method :post) (= uri "/create-page"))
          (create-page-handler conn graph-dir request)

          (and (= method :post) (= uri "/append"))
          (append-handler conn graph-dir request)

          (and (= method :post) (= uri "/update-block"))
          (update-block-handler conn graph-dir request)

          (and (= method :post) (= uri "/insert-block"))
          (insert-block-handler conn graph-dir request)

          (and (= method :post) (= uri "/delete-block"))
          (delete-block-handler conn graph-dir request)

          (and (= method :post) (= uri "/change-block-level"))
          (change-block-level-handler conn graph-dir request)

          (and (= method :post) (= uri "/reindex"))
          (reindex-handler conn graph-dir request)

          (and (= method :post) (= uri "/reindex-file"))
          (reindex-file-handler conn graph-dir request)

          (and (= method :post) (= uri "/set-page-property"))
          (set-page-property-handler conn graph-dir request)

          (and (= method :post) (= uri "/push/subscribe"))
          (push-subscribe-handler graph-dir request)

          (and (= method :post) (= uri "/push/unsubscribe"))
          (push-unsubscribe-handler graph-dir request)

          (and (= method :post) (= uri "/upload"))
          (upload-handler graph-dir request)

          (and (= method :get) (str/starts-with? uri "/assets/"))
          (asset-handler graph-dir request uri)

          (and (= method :get) (str/starts-with? uri "/block/"))
          (let [suffix (subs uri 7)]
            (if (str/ends-with? suffix "/refs")
              (block-refs-handler conn (assoc-in request [:params "uuid"]
                                                 (subs suffix 0 (- (count suffix) 5))))
              (block-handler conn (assoc-in request [:params "uuid"] suffix))))

          :else
          {:status  404
           :headers {"Content-Type" "application/json"}
           :body    (json/generate-string {:error "not found" :path uri})})))))
