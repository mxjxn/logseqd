(ns logseq-datalog.parser
  (:require [clojure.string :as str])
  (:import [java.io File]))

;; ─── Property Lines ───────────────────────────────────────────
;; Logseq property syntax: `key:: value`
;; Rules (from logseq/graph_parser/property.cljs):
;;   - property name has NO spaces
;;   - names are lower-cased; `_` is renamed to `-`
;;   - empty values (`key:: `) are dropped (not queryable)
;;   - we only accept sane keys: start with a letter, then [a-z0-9-]

(def ^:private prop-line-re #"^(\S+?)::(?:\s+(.*))?$")
(def ^:private valid-key-re #"^[a-z][a-z0-9-]*$")

(defn- normalize-key [k]
  (-> k str/lower-case (str/replace "_" "-")))

(defn parse-property-line
  "Parse a single `key:: value` line (already stripped of indentation and
  any leading block marker). Returns [normalized-key value-string] or nil.
  Drops empty values and invalid property names."
  [line]
  (when-let [m (re-matches prop-line-re (str/trimr line))]
    (let [k (normalize-key (nth m 1))
          v (some-> (nth m 2) str/trim)]
      (when (and (re-matches valid-key-re k)
                 (not (str/blank? v)))
        [k v]))))

;; ─── Frontmatter / Page Properties ────────────────────────────
;; Two accepted forms:
;;   1. Bare leading `key:: value` lines (real Logseq format)
;;   2. `---` fenced YAML-ish block (legacy / earlier agent writes)

(defn- fenced? [lines]
  (= "---" (str/trim (or (first lines) ""))))

(defn- parse-fenced [lines]
  (let [rest-lines (rest lines)
        end-idx (first (keep-indexed #(when (= (str/trim %2) "---") %1) rest-lines))]
    (if (nil? end-idx)
      {:frontmatter {} :body (vec lines)}
      (let [fm-lines (take end-idx rest-lines)
            fm (into {}
                     (for [line fm-lines
                           :let [[k v] (str/split line #"::?\s+" 2)]
                           :when (and k v (not (str/blank? v)))]
                       [(normalize-key (str/trim k)) (str/trim v)]))
            body (drop (inc end-idx) rest-lines)]
        {:frontmatter fm :body (vec body)}))))

(defn- parse-bare [lines]
  ;; Grab consecutive property lines from the very top of the file.
  ;; Stops at the first blank line or the first `- ` block.
  (let [prop-pairs (->> lines
                        (take-while #(parse-property-line %))
                        (map parse-property-line)
                        (remove nil?))
        fm (into {} prop-pairs)
        body (drop (count prop-pairs) lines)]
    {:frontmatter fm :body (vec body)}))

(defn- split-tags [fm]
  ;; `tags::`/`alias::` are comma-separated page references.
  (if-let [t (get fm "tags")]
    (let [tags (->> (str/split t #",") (map str/trim) (remove str/blank?) vec)]
      (assoc fm "tags" tags))
    fm))

(defn- parse-frontmatter [lines]
  (let [{:keys [frontmatter body]} (if (fenced? lines)
                                     (parse-fenced lines)
                                     (parse-bare lines))]
    {:frontmatter (split-tags frontmatter) :body body}))

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
     :properties {}
     :children   []}))

;; ─── Flat Block + Property Collection ─────────────────────────
;; A block starts on a `- ` line. Any following non-dash line that is a
;; `key:: value` property line is attached to that block's :properties.
;; Non-dash, non-property lines are treated as content continuation and
;; appended to the block's content (preserves multi-line blocks).

(def ^:private uuid-pattern
  #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn- extract-block-uuid [value]
  (when-let [m (re-matches (re-pattern (str "(" uuid-pattern ")")) value)]
    (second m)))

(defn- stripped-continuation [line]
  ;; Remove leading indentation (tabs/spaces) for a non-dash continuation line.
  (str/replace line #"^[\t ]+" ""))

(defn- collect-flat-blocks [processed]
  (loop [lines processed
         blocks []
         cur    nil]
    (if (empty? lines)
      (if cur (conj blocks cur) blocks)
      (let [line (first lines)]
        (cond
          (str/blank? line)
          (recur (rest lines) blocks cur)

          (block-line? line)
          (let [blocks' (if cur (conj blocks cur) blocks)
                stripped (strip-block-marker line)
                new-block (make-block stripped (indent-level line))]
            ;; A block whose own content IS a property line (e.g.
            ;; `- template:: morning-journal`) — store the property AND
            ;; keep the original text as content (matches Logseq behavior,
            ;; which retains the raw line in :block/content).
            (if-let [[k v] (parse-property-line stripped)]
              (recur (rest lines) blocks'
                     (cond-> (assoc new-block :properties {k v})
                       (and (= k "id") (extract-block-uuid v))
                       (assoc :uuid (extract-block-uuid v))))
              (recur (rest lines) blocks' new-block)))

          ;; continuation line belonging to the current block
          cur
          (let [stripped (stripped-continuation line)]
            (if-let [[k v] (parse-property-line stripped)]
              (recur (rest lines) blocks
                     (-> cur
                         (assoc-in [:properties k] v)
                         ;; accumulate refs/block-refs from property values
                         (#(if-let [br (seq (extract-block-refs v))]
                             (update % :block-refs (fn [xs] (vec (concat xs br))))
                             %))
                         (#(if-let [pr (seq (extract-page-refs v))]
                             (update % :refs (fn [xs] (vec (concat xs pr))))
                             %))
                         ;; extract uuid from id:: <uuid>
                         (#(if (and (= k "id") (extract-block-uuid v))
                             (assoc % :uuid (extract-block-uuid v))
                             %))))
              ;; plain continuation content — append to block content
              (recur (rest lines) blocks
                     (update cur :content #(str % "\n" stripped)))))

          ;; stray line before any block — ignore
          :else
          (recur (rest lines) blocks cur))))))

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
  Returns {:title :tags :properties :journal? :file :blocks}."
  [^String file-path & {:keys [journal?] :or {journal? false}}]
  (let [content (slurp file-path)
        lines (str/split-lines content)
        {:keys [frontmatter body]} (parse-frontmatter lines)
        processed (preprocess-code-blocks body)
        flat-blocks (collect-flat-blocks processed)
        block-tree (build-tree flat-blocks)
        ^File f (File. file-path)
        filename (.getName f)
        title (or (get frontmatter "title")
                  (if journal?
                    (str/replace filename #"\.md$" "")
                    (-> filename
                        (str/replace #"\.md$" "")
                        (str/replace #"___" "/"))))
        ;; page-level properties = frontmatter minus title/tags
        page-props (dissoc frontmatter "title" "tags")]
    {:title      title
     :tags       (get frontmatter "tags" [])
     :properties page-props
     :journal?   journal?
     :file       file-path
     :blocks     block-tree}))
