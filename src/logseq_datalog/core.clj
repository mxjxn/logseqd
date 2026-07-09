(ns logseq-datalog.core
  (:require [datascript.core :as d]
            [ring.adapter.jetty :as jetty]
            [logseq-datalog.schema :as schema]
            [logseq-datalog.indexer :as indexer]
            [logseq-datalog.api :as api])
  (:gen-class))

(defn -main
  "Start the headless Logseq Datalog service.
  Args: graph-dir [port]"
  [& args]
  (when (empty? args)
    (println "Usage: logseq-datalog <graph-dir> [port]")
    (System/exit 1))
  (let [graph-dir (first args)
        port      (if (> (count args) 1)
                    (Integer/parseInt (second args))
                    8471)
        conn      (d/create-conn schema/schema)]
    (println "Logseq Datalog Service")
    (println "──────────────────────")
    (println "Graph:" graph-dir)
    (println "Indexing...")
    (let [stats (indexer/index-graph! conn graph-dir)]
      (println (format "  %d pages, %d blocks, %d tags"
                       (:pages stats) (:blocks stats) (:tags stats))))
    (println "Server ready on port" port)
    (jetty/run-jetty (api/make-app conn graph-dir)
                     {:port port :join? true})))
