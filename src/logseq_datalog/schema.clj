(ns logseq-datalog.schema)

(def schema
  {:block/id        {:db/unique :db.unique/identity}
   :block/content   {}
   :block/todo      {}
   :block/level     {}
   :block/order     {}
   :block/source    {}
   :block/tags      {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :block/refs      {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :block/block-refs {:db/cardinality :db.cardinality/many}
   :block/embeds    {:db/cardinality :db.cardinality/many}
   :block/page      {:db/valueType :db.type/ref}
   :block/parent    {:db/valueType :db.type/ref}
   :page/title      {:db/unique :db.unique/identity}
   :page/file       {}
   :page/tags       {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :page/journal    {}
   :tag/name        {:db/unique :db.unique/identity}})
