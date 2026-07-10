(ns logseq-datalog.schema)

;; Note on properties (option A — namespaced dynamic attributes):
;; Block/page properties like `type:: job` are stored as dynamic DataScript
;; attributes under the `:prop/` namespace, e.g. `:prop/type "job"`.
;; DataScript does NOT require these to be declared here — undeclared
;; attributes default to :db.cardinality/one, non-ref, non-unique, which is
;; exactly what we want for string-valued properties. This lets us query
;; `[?b :prop/type "job"]` without pre-registering every possible key.
;; Only attributes needing special treatment (refs, uniqueness, cardinality)
;; are declared below.

(def schema
  {:block/id        {:db/unique :db.unique/identity}
   :block/uuid      {:db/unique :db.unique/identity}
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
   :block/property-keys {:db/cardinality :db.cardinality/many}
   :block/page      {:db/valueType :db.type/ref}
   :block/parent    {:db/valueType :db.type/ref}
   :page/title      {:db/unique :db.unique/identity}
   :page/file       {}
   :page/tags       {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :page/property-keys {:db/cardinality :db.cardinality/many}
   :page/journal    {}
   :tag/name        {:db/unique :db.unique/identity}})
