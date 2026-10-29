(ns logseq-datalog.write-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.string :as str]
            [datascript.core :as d]
            [logseq-datalog.api :as api]
            [logseq-datalog.indexer :as indexer]
            [logseq-datalog.schema :as schema])
  (:import [java.nio.file Files]
           [java.io ByteArrayInputStream]))

(def fixture
  (str "title:: Test\n\n- Parent A\n\t- repeated\n"
       "- Parent B\n\t- repeated\n\t\t- deep content\n\t\t\tcontinuation\n"
       "\t- TODO A task\n\t\tid:: 668c9f12-3456-7890-abcd-ef1234567890\n"
       "- ```clojure\n- code-not-a-block\ncode:: example\n```\n- final\n"))

(defn with-graph [f]
  (let [dir (.toFile (Files/createTempDirectory "logseqd-write-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file dir "pages" "Test.md")
        conn (d/create-conn schema/schema)]
    (.mkdirs (.getParentFile file))
    (spit file fixture)
    (indexer/index-graph! conn (.getPath dir))
    (try (f conn (.getPath dir) file)
         (finally (doseq [file (reverse (file-seq dir))] (.delete file))))))

(defn blocks [conn]
  (map first (d/q '[:find (pull ?b [* {:block/parent [:block/content]}])
                   :where [?b :block/id]] @conn)))

(defn request [body]
  {:body (ByteArrayInputStream. (.getBytes (pr-str body) "UTF-8"))})

(deftest duplicate-nested-block-targets-its-source-location
  (with-graph
    (fn [conn dir file]
      (let [target (first (filter #(and (= "repeated" (:block/content %))
                                        (= "Parent B" (get-in % [:block/parent :block/content]))) (blocks conn)))
            result (api/update-block-handler conn dir (request {:id (:block/id target) :content "changed second"}))]
        (is (= 200 (:status result)))
        (is (str/includes? (slurp file) "- Parent A\n\t- repeated\n"))
        (is (str/includes? (slurp file) "- Parent B\n\t- changed second\n"))
        (is (= "changed second" (:block/content (first (filter #(= (:block-id (json/parse-string (:body result) true)) (:block/id %)) (blocks conn))))))))))

(deftest nested-multiline-update-preserves-neighbours
  (with-graph
    (fn [conn dir file]
      (let [target (first (filter #(str/starts-with? (:block/content %) "deep content") (blocks conn)))
            result (api/update-block-handler conn dir (request {:id (:block/id target) :content "new deep\nnew continuation"}))]
        (is (= 200 (:status result)))
        (is (str/includes? (slurp file) "\t\t- new deep\n\t\t\tnew continuation\n"))
        (is (= 2 (count (re-seq #"- repeated" (slurp file)))))))))

(deftest task-update-keeps-status-and-properties
  (with-graph
    (fn [conn dir file]
      (let [target (first (filter #(= "A task" (:block/content %)) (blocks conn)))
            result (api/update-block-handler conn dir (request {:id (:block/id target) :content "Updated task"}))]
        (is (= 200 (:status result)))
        (is (str/includes? (slurp file) "\t- TODO Updated task\n\t\tid:: 668c9f12-3456-7890-abcd-ef1234567890"))))))

(deftest insertion-after-code-fence-ignores-fake-bullets
  (with-graph
    (fn [conn dir file]
      (let [target (first (filter #(= "final" (:block/content %)) (blocks conn)))
            result (api/insert-block-handler conn dir (request {:page "Test" :after-block-id (:block/id target)
                                                               :content "after final" :level 0}))]
        (is (= 200 (:status result)))
        (is (str/ends-with? (slurp file) "- final\n- after final"))))))

(deftest external-file-change-refuses-stale-write
  (with-graph
    (fn [conn dir file]
      (let [target (first (filter #(= "final" (:block/content %)) (blocks conn)))]
        (spit file "- external addition\n" :append true)
        (.setLastModified file (+ (.lastModified file) 2000))
        (let [before (slurp file)
              result (api/update-block-handler conn dir (request {:id (:block/id target) :content "must not save"}))]
          (is (= 404 (:status result)))
          (is (= before (slurp file))))))))

(deftest replacing-fenced-content-does-not-turn-code-into-properties
  (with-graph
    (fn [conn dir file]
      (let [target (first (filter #(str/starts-with? (:block/content %) "```clojure") (blocks conn)))
            result (api/update-block-handler conn dir (request {:id (:block/id target) :content "replacement"}))]
        (is (= 200 (:status result)))
        (is (not (str/includes? (slurp file) "code:: example")))
        (is (str/includes? (slurp file) "- replacement\n- final"))))))

(deftest deleting-second-duplicate-keeps-first-and-inherits-children
  (with-graph
    (fn [conn dir file]
      (let [target (first (filter #(and (= "repeated" (:block/content %))
                                        (= "Parent B" (get-in % [:block/parent :block/content]))) (blocks conn)))
            result (api/delete-block-handler conn dir (request {:block-id (:block/id target) :inherit-children true}))]
        (is (= 200 (:status result)))
        (is (= 1 (count (re-seq #"- repeated" (slurp file)))))
        (is (str/includes? (slurp file) "- Parent B\n\t- deep content"))))))

(deftest indenting-second-duplicate-moves-its-subtree-only
  (with-graph
    (fn [conn dir file]
      (let [target (first (filter #(and (= "repeated" (:block/content %))
                                        (= "Parent B" (get-in % [:block/parent :block/content]))) (blocks conn)))
            result (api/change-block-level-handler conn dir (request {:block-id (:block/id target) :new-level 2}))]
        (is (= 200 (:status result)))
        (is (str/includes? (slurp file) "- Parent A\n\t- repeated\n"))
        (is (str/includes? (slurp file) "- Parent B\n\t\t- repeated\n\t\t\t- deep content"))))))

(deftest namespaced-page-uses-logseq-filename-encoding
  (with-graph
    (fn [conn dir _file]
      ;; create-page encodes '/' as '___' (Logseq's namespace encoding)
      (let [result (api/create-page-handler conn dir (request {:title "proj/alpha"}))
            body   (json/parse-string (:body result) true)]
        (is (= 200 (:status result)))
        (is (= "proj/alpha" (:title body)))
        (is (.exists (io/file dir "pages" "proj___alpha.md")))
        ;; append resolves the same encoded file instead of forking foo_bar.md
        (let [r2 (api/append-handler conn dir (request {:title "proj/alpha" :content "child block"}))]
          (is (= 200 (:status r2)))
          (is (str/includes? (slurp (io/file dir "pages" "proj___alpha.md")) "child block")))
        ;; update-block finds the source file through the encoding
        (let [_      (indexer/index-file! conn dir "proj___alpha.md")
              target (ffirst (d/q '[:find (pull ?b [* {:block/page [:page/title]}])
                                    :where [?b :block/content "child block"]] @conn))
              r3     (api/update-block-handler conn dir (request {:id (:block/id target) :content "edited in place"}))]
          (is (some? target))
          (is (= 200 (:status r3)))
          (is (str/includes? (slurp (io/file dir "pages" "proj___alpha.md")) "edited in place")))
        ;; a fresh index decodes the filename back to the namespaced title
        (let [conn2 (d/create-conn schema/schema)]
          (indexer/index-graph! conn2 dir)
          (is (some #(= "proj/alpha" %)
                    (map first (d/q '[:find ?t :where [_ :page/title ?t]] @conn2)))))))))
