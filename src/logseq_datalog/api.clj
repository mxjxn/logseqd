(ns logseq-datalog.api
  (:require [datascript.core :as d]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ring.middleware.params :as params]
            [logseq-datalog.indexer :as indexer])
  (:import [java.io PushbackReader InputStreamReader]))

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

;; ─── Handlers ─────────────────────────────────────────────────

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
  (let [results (d/q '[:find (pull ?p [:page/title :page/journal :page/file])
                       :where [?p :page/title]] @conn)]
    (json-resp {:pages (vec results)})))

(defn page-handler [conn request]
  (let [raw-title (get-in request [:params "title"])
        title (java.net.URLDecoder/decode raw-title "UTF-8")
        results (d/q '[:find (pull ?b [:block/content :block/level :block/order
                                       :block/todo
                                       {:block/tags   [:tag/name]}
                                       {:block/refs   [:page/title]}
                                       {:block/parent [:block/id :block/content]}])
                       :in $ ?title
                       :where [?b :block/page ?p]
                              [?p :page/title ?title]]
                     @conn title)
        blocks (->> results
                    (map first)
                    (sort-by :block/order)
                    vec)]
    (if (seq blocks)
      (json-resp {:title title :blocks blocks})
      (json-resp {:error "not found" :title title} 404))))

(defn tags-handler [conn _]
  (let [results (d/q '[:find ?name (count ?b)
                       :where [?t :tag/name ?name]
                              [?b :block/tags ?t]] @conn)]
    (json-resp {:tags (vec (for [[name cnt] results]
                             {:name name :count cnt}))})))

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

(defn reindex-handler [conn graph-dir _]
  (try
    (let [stats (indexer/index-graph! conn graph-dir)]
      (json-resp {:status "ok" :stats stats}))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

(defn reindex-file-handler [conn graph-dir request]
  (try
    (let [body (read-edn-body request)
          file-name (:file body)]
      (if (str/blank? file-name)
        (json-resp {:error "Must provide :file (filename relative to graph dir)"} 400)
        (let [result (indexer/index-file! conn graph-dir file-name)]
          (json-resp result))))
    (catch Exception e
      (json-resp {:error (.getMessage e)} 500))))

;; ─── Router ───────────────────────────────────────────────────

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

          (and (= method :post) (= uri "/reindex"))
          (reindex-handler conn graph-dir request)

          (and (= method :post) (= uri "/reindex-file"))
          (reindex-file-handler conn graph-dir request)

          :else
          {:status  404
           :headers {"Content-Type" "application/json"}
           :body    (json/generate-string {:error "not found" :path uri})})))))
