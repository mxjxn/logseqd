(ns logseq-datalog.api
  (:require [datascript.core :as d]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ring.middleware.params :as params]
            [logseq-datalog.indexer :as indexer])
  (:import [java.io PushbackReader InputStreamReader File]))

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
        results (d/q '[:find (pull ?b [:block/content
                                       {:block/page [:page/title]}
                                       {:block/tags [:tag/name]}])
                       :in $ ?search
                       :where [?b :block/content ?content]
                              [(clojure.string/includes? ?content ?search)]]
                     @conn q)]
    (json-resp {:query  q
                :results (vec (map first results))
                :count   (count results)})))

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
          ;; Append the block
          (let [indent (apply str (repeat level "\t"))
                block-line (str indent "- " content "\n")]
            (spit filepath block-line :append true))
          ;; Reindex this file (index-file! checks both pages/ and journals/)
          (let [stats (indexer/index-file! conn graph-dir filename)]
            (json-resp {:status "ok" :file (str target-dir "/" filename)
                        :stats stats})))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn update-block-handler
  "POST /update-block — replace the content of an existing block.
   Body: EDN {:id \"block-id\" :content \"new content\"}
   block-id format: \"PageTitle-Counter\" (e.g. \"Mnemosyne-1\")
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
                          ;; Strategy 1: exact content match (most reliable)
                          indent      (apply str (repeat level "\t"))
                          exact-match (when old-content
                                        (first (keep-indexed
                                                 (fn [i line]
                                                   (when (= line (str indent "- " old-content))
                                                     i))
                                                 lines)))
                          ;; Strategy 2: count bullet lines at this level to find order
                          order-match (when-not exact-match
                                        (let [bullet-idx (atom -1)]
                                          (first (keep-indexed
                                                   (fn [i line]
                                                     (let [line-level (count (take-while #(= % "\t") line))]
                                                       (when (and (= line-level level)
                                                                  (re-find #"^\t*- " line))
                                                         (swap! bullet-idx inc)
                                                         (when (= @bullet-idx order) i))))
                                                   lines))))
                          idx (or exact-match order-match)]
                      (if (nil? idx)
                        (json-resp {:error "could not locate block in file"
                                    :block-id block-id :order order :level level
                                    :content-preview (str/trim (or old-content ""))} 404)
                        (let [new-line  (str indent "- " content)
                              new-lines (vec (concat (subvec lines 0 idx)
                                                      [new-line]
                                                      (subvec lines (inc idx))))]
                          (spit filepath (str/join "\n" new-lines))
                          (indexer/index-file! conn graph-dir filename)
                          ;; block-id counter increments on reindex, so fetch the new id
                          (let [new-result (d/q '[:find (pull ?b [:block/id])
                                               :in $ ?pt ?ord ?lvl
                                               :where [?b :block/id ?bid]
                                                      [?b :block/order ?ord]
                                                      [?b :block/level ?lvl]
                                                      [?p :page/title ?pt]
                                                      [?b :block/page ?p]]
                                             @conn page-title order level)
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

(defn- find-block-line-index
  "Find the line index of a block in the file lines vector.
  Uses exact content match first, then bullet-line counting at target level."
  [lines level order old-content]
  (let [indent (apply str (repeat level "\t"))
        exact-match (when old-content
                      (first (keep-indexed
                               (fn [i line]
                                 (when (= line (str indent "- " old-content))
                                   i))
                               lines)))
        order-match (when-not exact-match
                      (let [bullet-idx (atom -1)]
                        (first (keep-indexed
                                 (fn [i line]
                                   (let [line-level (count (take-while #(= % "\t") line))]
                                     (when (and (= line-level level)
                                                (re-find #"^\t*- " line))
                                       (swap! bullet-idx inc)
                                       (when (= @bullet-idx order) i))))
                                 lines))))]
    (or exact-match order-match)))

(defn- skip-children
  "Starting from the line after idx, skip forward past all lines whose indent
  level is strictly greater than target-level. Returns the index after the
  last child (or idx+1 if no children follow)."
  [lines idx target-level]
  (loop [i (inc idx)]
    (if (>= i (count lines))
      i
      (let [line-level (count (take-while #(= % "\t") (nth lines i)))]
        (if (> line-level target-level)
          (recur (inc i))
          i)))))

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
                          idx        (find-block-line-index lines block-level order old-content)]
                      (if (nil? idx)
                        (json-resp {:error "could not locate block in file"
                                    :block-id after-id :order order :level block-level} 404)
                        (let [insert-pos (skip-children lines idx block-level)
                              new-indent (apply str (repeat level "\t"))
                              new-line   (str new-indent "- " content)
                              new-lines  (vec (concat (subvec lines 0 insert-pos)
                                                      [new-line]
                                                      (subvec lines insert-pos)))]
                          (spit filepath (str/join "\n" new-lines))
                          (indexer/index-file! conn graph-dir filename)
                          (let [new-order (inc order)
                                new-result (d/q '[:find (pull ?b [:block/id])
                                                 :in $ ?pt ?ord ?lvl
                                                 :where [?b :block/id ?bid]
                                                        [?b :block/order ?ord]
                                                        [?b :block/level ?lvl]
                                                        [?p :page/title ?pt]
                                                        [?b :block/page ?p]]
                                                @conn page-title new-order level)
                                new-bid (-> new-result ffirst :block/id)]
                            (json-resp {:status "ok"
                                        :block-id new-bid
                                        :after-block-id after-id
                                        :page page-title
                                        :file (str target-dir "/" filename)})))))))))
            (json-resp {:error "block not found" :block-id after-id} 404)))))
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
                          idx   (find-block-line-index lines cur-level order old-content)]
                      (if (nil? idx)
                        (json-resp {:error "could not locate block in file"
                                    :block-id block-id :order order :level cur-level} 404)
                        (let [end-idx (skip-children lines idx cur-level)
                              delta   (- new-level cur-level)
                              subtree (subvec lines idx end-idx)
                              changed-sub (mapv (fn [line]
                                                  (let [line-level (count (take-while #(= % \tab) line))
                                                        rest-part  (subs line line-level)]
                                                    (cond
                                                      (zero? line-level) line
                                                      (= line-level cur-level)
                                                      (str (apply str (repeat new-level \tab)) rest-part)
                                                      :else
                                                      (let [new-lvl (max (+ new-level 1)
                                                                        (+ line-level delta))]
                                                        (str (apply str (repeat new-lvl \tab)) rest-part)))))
                                                subtree)
                              new-lines (vec (concat (subvec lines 0 idx)
                                                      changed-sub
                                                      (subvec lines end-idx)))]
                          (spit filepath (str/join "\n" new-lines))
                          (indexer/index-file! conn graph-dir filename)
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
      (if-not filename
        (json-resp {:error "Request body must include :file"} 400)
        (let [stats (indexer/index-file! conn graph-dir filename)]
          (json-resp {:status "ok" :stats stats}))))
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

          (and (= method :get) (str/starts-with? uri "/page/"))
          (page-handler conn (assoc-in request [:params "title"] (subs uri 6)))

          (and (= method :get) (= uri "/tags"))
          (tags-handler conn request)

          (and (= method :get) (= uri "/search"))
          (search-handler conn request)

          (and (= method :get) (= uri "/property"))
          (property-handler conn request)

          (and (= method :post) (= uri "/append"))
          (append-handler conn graph-dir request)

          (and (= method :post) (= uri "/update-block"))
          (update-block-handler conn graph-dir request)

          (and (= method :post) (= uri "/insert-block"))
          (insert-block-handler conn graph-dir request)

          (and (= method :post) (= uri "/change-block-level"))
          (change-block-level-handler conn graph-dir request)

          (and (= method :post) (= uri "/reindex"))
          (reindex-handler conn graph-dir request)

          (and (= method :post) (= uri "/reindex-file"))
          (reindex-file-handler conn graph-dir request)

          (and (= method :post) (= uri "/push/subscribe"))
          (push-subscribe-handler graph-dir request)

          (and (= method :post) (= uri "/push/unsubscribe"))
          (push-unsubscribe-handler graph-dir request)

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
