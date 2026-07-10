(ns logseq-datalog.indexer
  (:require [datascript.core :as d]
            [clojure.string :as str]
            [logseq-datalog.parser :as parser]
            [logseq-datalog.schema :as schema])
  (:import [java.io File]))

;; ─── File Discovery ───────────────────────────────────────────

(defn- find-md-files [dir]
  (let [f (File. dir)]
    (if (.isDirectory f)
      (->> (.listFiles f)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".md"))
           (sort-by #(.getName %)))
      [])))

;; ─── Recursive Block Walkers ──────────────────────────────────

(defn- walk-blocks
  "Depth-first walk over block tree, calling (f block) on each.
  f should return a collection; results are concatenated."
  [f block]
  (concat (f block)
          (mapcat #(walk-blocks f %) (:children block))))

(defn- collect-tags [blocks]
  (into #{} (mapcat #(walk-blocks :tags %) blocks)))

(defn- collect-refs [blocks]
  (into #{} (mapcat #(walk-blocks :refs %)) blocks))

;; ─── Transaction Builders ─────────────────────────────────────

(def ^:private tempid-counter (atom 0))
(def ^:private block-id-counter (atom 0))

(defn- next-tempid []
  (let [n (swap! tempid-counter inc)]
    (- n)))

(defn- next-block-id [title]
  (str title "-" (swap! block-id-counter inc)))

(defn- prop-attr
  "Dynamic DataScript attribute for a property key, e.g. \"type\" -> :prop/type."
  [k]
  (keyword "prop" (str k)))

(defn- props->tx
  "Turn a {key value} property map into a partial tx map of dynamic
  :prop/<key> attributes plus a :block/property-keys (or :page/property-keys)
  list of the key names for enumeration."
  [props keys-attr]
  (when (seq props)
    (into {keys-attr (vec (keys props))}
          (for [[k v] props]
            [(prop-attr k) (str v)]))))

(defn- page->block-tx
  "Convert a parsed page's block tree into transaction data.
  Uses negative integer tempids for parent-child links."
  [{:keys [title blocks]} this-page-eid page-lookup tag-lookup]
  (let [result (atom [])]
    (letfn [(process [block parent-tempid order]
              (let [tid (next-tempid)
                    bid (next-block-id title)
                    tag-refs (vec (for [t (:tags block)
                                        :let [t (str t)]
                                        :when (tag-lookup (str t))]
                                   [:tag/name (str t)]))
                    page-refs (vec (for [r (:refs block)
                                        :let [r (str r)]
                                        :when (page-lookup (str r))]
                                    [:page/title (str r)]))
                    block-refs-vals (vec (map str (:block-refs block)))
                    embed-vals (vec (map str (:embeds block)))
                    prop-tx (props->tx (:properties block) :block/property-keys)
                    tx (cond-> {:db/id        tid
                                :block/id     bid
                                :block/content (str (:content block))
                                :block/level  (:level block)
                                :block/order  order
                                :block/page   this-page-eid
                                :block/source title}
                         (:uuid block)              (assoc :block/uuid (:uuid block))
                         (:todo block)              (assoc :block/todo (name (:todo block)))
                         parent-tempid              (assoc :block/parent parent-tempid)
                         (seq tag-refs)             (assoc :block/tags tag-refs)
                         (seq page-refs)            (assoc :block/refs page-refs)
                         (seq block-refs-vals)      (assoc :block/block-refs block-refs-vals)
                         (seq embed-vals)           (assoc :block/embeds embed-vals)
                         prop-tx                    (merge prop-tx))]
                (swap! result conj tx)
                (doseq [[i child] (map-indexed vector (:children block))]
                  (process child tid i))))]
      (doseq [[i block] (map-indexed vector blocks)]
        (process block nil i))
      @result)))

;; ─── Main Indexer ─────────────────────────────────────────────

(defn index-file!
  "Incrementally reindex a single markdown file within the graph.
  filename is relative to graph-dir (e.g. \"SomePage.md\").
  Falls back to journals/ if the file is not found in pages/.

  Strategy to avoid DataScript retractEntity/EID-reuse bugs:
  - Retract existing blocks by :block/source (attribute-level, not page retract)
  - Upsert the page entity (:page/title has :db.unique/identity)
  - Use [:page/title title] lookup ref for :block/page so no stale EID is needed

  Returns {:pages 1 :blocks N :tags N}."
  [conn graph-dir filename]
  (let [pages-path    (str graph-dir "/pages/" filename)
        journals-path (str graph-dir "/journals/" filename)
        file-path (cond
                    (.exists (File. pages-path))    pages-path
                    (.exists (File. journals-path)) journals-path
                    :else (throw (ex-info (str "File not found: " filename)
                                          {:filename filename})))
        journal? (str/includes? file-path "/journals/")
        parsed   (parser/parse-file file-path :journal? journal?)
        title    (:title parsed)

        ;; Step 1: Retract existing blocks for this page.
        ;; :block/source stores the page title, so we find blocks whose source
        ;; matches the current page title.
        existing-block-eids (d/q '[:find [?b ...]
                                    :in $ ?source
                                    :where [?b :block/source ?source]]
                                   @conn title)
        retract-txs (mapv #(vector :db/retractEntity %) existing-block-eids)

        ;; Step 2: Collect inline tags from the freshly-parsed blocks
        new-tags (collect-tags (:blocks parsed))

        ;; Step 3: Transact retractions + new tag upserts + page upsert atomically.
        ;; :page/title has :db.unique/identity so DataScript upserts the page in-place.
        tag-entry   (fn [t] {:tag/name (str t)})
        tag-txs     (mapv tag-entry new-tags)
        page-tx (cond-> {:page/title   title
                         :page/file    (:file parsed)
                         :page/journal (:journal? parsed)}
                   (seq (:tags parsed))
                   (assoc :page/tags (mapv tag-entry (:tags parsed)))

                   (seq (:properties parsed))
                   (merge (props->tx (:properties parsed) :page/property-keys)))
        _ (d/transact! conn (-> retract-txs (into tag-txs) (conj page-tx)))

        ;; Step 4: Build tag lookup after the above transaction
        tag-lookup (into {}
                         (d/q '[:find ?name ?e
                                 :where [?e :tag/name ?name]] @conn))

        ;; Step 5: Ensure stub pages exist for [[...]] references in blocks
        all-refs    (collect-refs (:blocks parsed))
        stub-titles (remove #{title} all-refs)
        _ (when (seq stub-titles)
            (d/transact! conn (mapv #(hash-map :page/title (str %)) stub-titles)))

        ;; Step 6: Build page lookup (includes stubs created above)
        page-lookup (into {}
                          (d/q '[:find ?t ?e
                                  :where [?e :page/title ?t]] @conn))

        ;; Step 7: Build block transaction data.
        ;; Use the [:page/title title] lookup ref for :block/page so we never
        ;; depend on a potentially-stale integer EID from a prior query.
        block-tx (page->block-tx parsed [:page/title title] page-lookup tag-lookup)]

    (when (seq block-tx)
      (d/transact! conn block-tx))

    {:pages  1
     :blocks (count block-tx)
     :tags   (count new-tags)}))

(defn index-graph!
  "Parse all markdown files and transact into DataScript.
  Returns {:pages N :blocks N :tags N}."
  [conn graph-dir]
  ;; Reset DB and counters
  (d/reset-conn! conn (d/empty-db schema/schema))
  (reset! tempid-counter 0)
  (reset! block-id-counter 0)

  (let [pages-dir (str graph-dir "/pages")
        journals-dir (str graph-dir "/journals")

        ;; Parse all files (resilient to individual parse errors)
        parse-or-nil (fn [f journal?]
                       (try
                         (parser/parse-file (.getAbsolutePath f) :journal? journal?)
                         (catch Exception e
                           (println "  WARN: Error parsing" (.getName f) ":" (.getMessage e))
                           nil)))
        parsed-pages (remove nil? (for [f (find-md-files pages-dir)]
                                    (parse-or-nil f false)))
        parsed-journals (remove nil? (for [f (find-md-files journals-dir)]
                                       (parse-or-nil f true)))
        all-parsed (concat parsed-pages parsed-journals)

        ;; Collect all inline tags from blocks
        all-tags (into #{}
                       (mapcat #(collect-tags (:blocks %)))
                       all-parsed)

        ;; Transact tags, build lookup
        _ (when (seq all-tags)
            (d/transact! conn (for [t all-tags] {:tag/name (str t)})))
        tag-lookup (into {}
                         (for [[eid name] (d/q '[:find ?e ?name
                                                  :where [?e :tag/name ?name]] @conn)]
                           [name eid]))

        ;; Collect all page titles from files
        file-titles (set (map :title all-parsed))

        ;; Transact page entities
        _ (d/transact! conn
                       (for [p all-parsed]
                         (cond-> {:page/title   (:title p)
                                  :page/file    (:file p)
                                  :page/journal (:journal? p)}
                           (seq (:tags p)) (assoc :page/tags
                                                  (vec (for [t (:tags p)
                                                             :let [t (str t)]
                                                             :when (tag-lookup (str t))]
                                                         [:tag/name (str t)])))

                           (seq (:properties p))
                           (merge (props->tx (:properties p) :page/property-keys)))))

        ;; Create stub pages for referenced-but-not-existing pages
        all-refs (into #{} (mapcat #(collect-refs (:blocks %))) all-parsed)
        stub-titles (remove file-titles all-refs)
        _ (when (seq stub-titles)
            (d/transact! conn (for [t stub-titles]
                                {:page/title (str t)})))

        ;; Build page lookup (file pages + stubs)
        page-lookup (into {}
                          (for [[eid title] (d/q '[:find ?e ?title
                                                   :where [?e :page/title ?title]] @conn)]
                            [title eid]))

        ;; Build and transact all blocks in one shot
        all-block-tx (mapcat (fn [p]
                               (page->block-tx p (page-lookup (:title p)) page-lookup tag-lookup))
                             all-parsed)]

    (when (seq all-block-tx)
      (d/transact! conn all-block-tx))

    {:pages (count all-parsed)
     :blocks (count all-block-tx)
     :tags (count all-tags)}))
