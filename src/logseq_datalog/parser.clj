(ns logseq-datalog.parser
  (:require [clojure.string :as str])
  (:import [java.io File]))

;; ─── Frontmatter ──────────────────────────────────────────────

(defn- parse-frontmatter [lines]
  (if (not= (str/trim (or (first lines) "")) "---")
    {:frontmatter {} :body (vec lines)}
    (let [rest-lines (rest lines)
          end-idx (first (keep-indexed #(when (= (str/trim %2) "---") %1) rest-lines))]
      (if (nil? end-idx)
        {:frontmatter {} :body (vec lines)}
        (let [fm-lines (take end-idx rest-lines)
              fm (into {}
                       (for [line fm-lines
                             :let [[k v] (str/split line #"::?\s+" 2)]
                             :when (and k v (not (str/blank? v)))]
                         [(keyword (str/trim k)) (str/trim v)]))
              tags (when-let [t (:tags fm)]
                     (->> (str/split t #",") (map str/trim) (remove str/blank?) vec))
              body (drop (inc end-idx) rest-lines)]
          {:frontmatter (if tags (assoc fm :tags tags) fm)
           :body (vec body)})))))

;; ─── Code Block Preprocessing ─────────────────────────────────
;; Joins triple-backtick code blocks into single "lines" so they
;; aren't broken across block boundaries.

(defn- count-fences [line]
  (count (re-seq #"```" line)))

(defn- preprocess-code-blocks [lines]
  (loop [lines lines result [] fence-count 0 acc []]
    (if (empty? lines)
      result
      (let [line (first lines)
            new-count (+ fence-count (count-fences line))
            was-in (odd? fence-count)
            now-in (odd? new-count)]
        (cond
          ;; Same-line open+close (rare) — treat as normal line
          (and (not was-in) (not now-in) (pos? (count-fences line)))
          (recur (rest lines) (conj result line) 0 [])

          ;; Entering code block
          (and (not was-in) now-in)
          (recur (rest lines) result new-count [line])

          ;; Inside code block
          (and was-in now-in)
          (recur (rest lines) result new-count (conj acc line))

          ;; Exiting code block — join accumulated lines
          (and was-in (not now-in))
          (recur (rest lines) (conj result (str/join "\n" (conj acc line))) 0 [])

          ;; Normal line
          :else
          (recur (rest lines) (conj result line) 0 []))))))

;; ─── Block Content Extraction ─────────────────────────────────

(defn- indent-level [line]
  (count (take-while #(= \tab %) line)))

(defn- block-line? [line]
  (boolean (re-find #"^\t*-\s" line)))

(defn- strip-block-marker [line]
  (let [level (indent-level line)
        after-indent (subs line level)]
    (str/trim (if (str/starts-with? after-indent "- ")
                (subs after-indent 2)
                (subs after-indent 1)))))

(defn- extract-todo [content]
  (when-let [m (re-matches #"(TODO|DOING|DONE|LATER|NOW|CANCEL)\s+(.*)" content)]
    [(keyword (second m)) (nth m 2)]))

(defn- extract-tags [content]
  (let [regular  (map second (re-seq #"#([\w/-]+)" content))
        backtick (map second (re-seq #"`#([\w/-]+)`" content))]
    (set (remove str/blank? (concat regular backtick)))))

(defn- extract-page-refs [content]
  (map second (re-seq #"\[\[([^\]]+)\]\]" content)))

(defn- extract-block-refs [content]
  (map second (re-seq #"\(\(([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\)\)" content)))

(defn- extract-embeds [content]
  (map second (re-seq #"\{\{embed\s+\(\(([0-9a-f-]+)\)\)\}\}" content)))

(defn- make-block [content level]
  (let [[todo content'] (or (extract-todo content) [nil content])]
    {:level      level
     :content    (str/trim content')
     :todo       todo
     :tags       (extract-tags content')
     :refs       (extract-page-refs content')
     :block-refs (extract-block-refs content')
     :embeds     (extract-embeds content')
     :children   []}))

;; ─── Tree Building ────────────────────────────────────────────
;; Uses a stack to nest blocks by indentation level.

(defn- build-tree [flat-blocks]
  (let [roots (volatile! [])
        stack (volatile! [])] ; each element: {:block map :children volatile! []}
    (doseq [block flat-blocks]
      (let [level (:level block)]
        ;; Pop until parent is at a shallower level
        (while (and (seq @stack)
                    (>= (:level (:block (last @stack))) level))
          (vswap! stack butlast))
        (let [node {:block block :children (volatile! [])}]
          (if (seq @stack)
            (vswap! (:children (last @stack)) conj node)
            (vswap! roots conj node))
          (vswap! stack conj node))))
    (letfn [(resolve-node [node]
              (assoc (:block node)
                     :children (mapv resolve-node @(:children node))))]
      (mapv resolve-node @roots))))

;; ─── File Parsing ─────────────────────────────────────────────

(defn parse-file
  "Parse a Logseq markdown file into structured data.
  Returns {:title :tags :journal? :file :blocks}."
  [^String file-path & {:keys [journal?] :or {journal? false}}]
  (let [content (slurp file-path)
        lines (str/split-lines content)
        {:keys [frontmatter body]} (parse-frontmatter lines)
        processed (preprocess-code-blocks body)
        flat-blocks (for [line processed
                          :when (block-line? line)]
                      (make-block (strip-block-marker line) (indent-level line)))
        block-tree (build-tree flat-blocks)
        ^File f (File. file-path)
        filename (.getName f)
        title (or (:title frontmatter)
                  (if journal?
                    (str/replace filename #"\.md$" "")
                    (-> filename
                        (str/replace #"\.md$" "")
                        (str/replace #"___" "/"))))]
    {:title    title
     :tags     (:tags frontmatter [])
     :journal? journal?
     :file     file-path
     :blocks   block-tree}))
