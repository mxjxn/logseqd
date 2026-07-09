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
  (into #{} (mapcat #(walk-blocks :refs %) blocks)))

;; ─── Transaction Builders ─────────────────────────────────────

(def ^:private tempid-counter (atom 0))
(def ^:private block-id-counter (atom 0))

(defn- next-tempid []
  (let [n (swap! tempid-counter inc)]
    (- n)))

(defn- next-block-id [title]
  (str title "-" (swap! block-id-counter inc)))

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
                    tx (cond-> {:db/id        tid
                                :block/id     bid
                                :block/content (str (:content block))
                                :block/level  (:level block)
                                :block/order  order
                                :block/page   this-page-eid
                                :block/source title}
                         (:todo block)              (assoc :block/todo (name (:todo block)))
                         parent-tempid              (assoc :block/parent parent-tempid)
                         (seq tag-refs)             (assoc :block/tags tag-refs)
                         (seq page-refs)            (assoc :block/refs page-refs)
                         (seq block-refs-vals)      (assoc :block/block-refs block-refs-vals)
                         (seq embed-vals)           (assoc :block/embeds embed-vals))]
                (swap! result conj tx)
                (doseq [[i child] (map-indexed vector (:children block))]
                  (process child tid i))))]
      (doseq [[i block] (map-indexed vector blocks)]
        (process block nil i))
      @result)))

;; ─── Incremental Single-File Indexer ──────────────────────────

(defn index-file!
  "Incrementally reindex a single file. Retracts all existing blocks for
  the page, re-parses the file, and transacts new blocks.
  Returns {:title :blocks :status} or {:error ...}."
  [conn graph-dir file-path]
  (try
    (let [^File f (File. (str graph-dir "/pages/" file-path))
          journal-f (File. (str graph-dir "/journals/" file-path))
          file (if (.exists f) f journal-f)
          journal? (.exists journal-f)
          parsed (parser/parse-file (.getAbsolutePath file) :journal? journal?)
          title (:title parsed)]
      ;; 1. Retract all existing blocks for this page
      (let [old-block-ids (d/q '[:find ?id
                                :in $ ?title
                                :where [?b :block/source ?title]
                                       [?b :block/id ?id]]
                              @conn title)]
        (when (seq old-block-ids)
          (d/transact! conn (vec (for [[bid] old-block-ids]
                                   [:db/retractEntity [:block/id bid]])))))
      ;; 2. Retract existing page entity
      (doseq [eid (d/q '[:find ?e
                         :in $ ?title
                         :where [?e :page/title ?title]]
                       @conn title)]
        (d/transact! conn [[:db/retractEntity eid]]))
      ;; 3. Ensure tag entities exist
      (let [all-tags (into #{} (mapcat #(walk-blocks :tags %) (:blocks parsed)))]
        (when (seq all-tags)
          (d/transact! conn (vec (for [t all-tags] {:tag/name (str t)})))))
      ;; 4. Transact page entity
      (let [tag-lookup (into {}
                            (for [[eid name] (d/q '[:find ?e ?name
                                                     :where [?e :tag/name ?name]] @conn)]
                              [name eid]))]
        (d/transact! conn
                     [(cond-> {:page/title   title
                              :page/file    (:file parsed)
                              :page/journal journal?}
                        (seq (:tags parsed))
                        (assoc :page/tags
                               (vec (for [t (:tags parsed)
                                          :let [t (str t)]
                                          :when (tag-lookup (str t))]
                                     [:tag/name (str t)]))))]))
      ;; 5. Ensure referenced pages exist as stubs
      (let [all-refs (into #{} (mapcat #(collect-refs (:blocks parsed))))]
        (doseq [r all-refs]
          (when (empty? (d/q '[:find ?e
                               :in $ ?t
                               :where [?e :page/title ?t]]
                             @conn (str r)))
            (d/transact! conn [{:page/title (str r)}]))))
      ;; 6. Build lookups and transact blocks
      (let [page-lookup (into {}
                              (for [[eid t] (d/q '[:find ?e ?title
                                                     :where [?e :page/title ?title]] @conn)]
                                [t eid]))
            tag-lookup (into {}
                             (for [[eid name] (d/q '[:find ?e ?name
                                                      :where [?e :tag/name ?name]] @conn)]
                               [name eid]))
            this-page-eid (page-lookup title)
            block-tx (page->block-tx parsed
                                       this-page-eid
                                       page-lookup
                                       tag-lookup)]
        (when (seq block-tx)
          (d/transact! conn block-tx))
        {:title  title
         :blocks (count block-tx)
         :status "ok"}))
    (catch Exception e
      {:error (.getMessage e)})))

;; ─── Main Indexer ─────────────────────────────────────────────

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
                                                         [:tag/name (str t)]))))))

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
