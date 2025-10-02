(ns mcp-vector-search.config
  (:refer-clojure :exclude [read])
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    (java.util.regex
      Pattern
      PatternSyntaxException)))

(def default-search-description
  "Default description for the search tool"
  "Search indexed documents using semantic similarity")

(defn read
  "Read and parse an EDN configuration file.
  Returns the parsed configuration map."
  [path]
  (-> path
      io/file
      slurp
      edn/read-string))

(defn- parse-segments
  "Parse a path spec string into segments.
  Returns a vector of segment maps with :type and type-specific keys."
  [path-str]
  (loop [remaining path-str
         segments  []]
    (if (empty? remaining)
      segments
      (cond
        ;; Named capture: (?<name>pattern)
        (str/starts-with? remaining "(?<")
        (let [name-end     (.indexOf remaining ">")
              close-paren  (.indexOf remaining ")")
              _            (when (= -1 name-end)
                             (throw
                              (ex-info
                               "Malformed capture: missing '>' in name"
                               {:path     path-str
                                :position (count (str/join segments))})))
              _            (when (= -1 close-paren)
                             (throw
                              (ex-info
                               "Malformed capture: missing closing ')'"
                               {:path     path-str
                                :position (count (str/join segments))})))
              name-part    (subs remaining 3 name-end)
              _            (when (str/blank? name-part)
                             (throw
                              (ex-info
                               "Malformed capture: missing capture name"
                               {:path     path-str
                                :position (count (str/join segments))})))
              pattern-part (subs remaining (inc name-end) close-paren)
              ;; Validate regex pattern
              _            (try
                             (Pattern/compile pattern-part)
                             (catch PatternSyntaxException e
                               (throw (ex-info "Invalid regex in capture"
                                               {:path         path-str
                                                :capture-name name-part
                                                :pattern      pattern-part
                                                :cause        (.getMessage e)}
                                               e))))]
          (recur (subs remaining (inc close-paren))
                 (conj segments {:type    :capture
                                 :name    name-part
                                 :pattern pattern-part})))

        ;; Recursive glob: **
        (str/starts-with? remaining "**")
        (recur (subs remaining 2)
               (conj segments {:type :glob :pattern "**"}))

        ;; Single-level glob: *
        (str/starts-with? remaining "*")
        (recur (subs remaining 1)
               (conj segments {:type :glob :pattern "*"}))

        ;; Literal text - collect until next special
        :else
        (let [indices      (keep #(let [idx (.indexOf remaining %)]
                                    (when (>= idx 0) idx))
                                 ["(?<" "**" "*"])
              next-special (when (seq indices) (apply min indices))
              literal-len  (or next-special (count remaining))
              literal      (subs remaining 0 literal-len)]
          (recur (subs remaining literal-len)
                 (conj segments {:type :literal :value literal})))))))

(defn- calculate-base-path
  "Calculate the base path from segments.
  Returns the concatenation of literal segments from the start until the
  first non-literal."
  [segments]
  (let [prefix-literals (take-while #(= :literal (:type %)) segments)]
    (str/join (map :value prefix-literals))))

(defn parse-path-spec
  "Parse a path specification string into a structured format.

  The path spec can contain:
  - Literal text (matched exactly)
  - Glob patterns: * (single level), ** (recursive)
  - Named captures: (?<name>regex-pattern)

  Returns a map with:
  - :segments - vector of segment maps
  - :base-path - the literal prefix path for filesystem walking"
  [path-str]
  (let [segments (parse-segments path-str)
        base-path (calculate-base-path segments)]
    {:segments segments
     :base-path base-path}))

(defn process-config
  "Process user config into internal format.

  Converts :sources (with raw :path strings) to :path-specs (with parsed
  segments).

  Each source entry should have:
  - :path - raw path spec string
  - :name (optional) - source name
  - additional keys become base-metadata

  Adds :description with default if not provided.

  Returns a config map with :path-specs ready for ingestion."
  [{:keys [sources description] :as config}]
  (cond-> {}
    sources (assoc :path-specs
                   (mapv (fn [{:keys [path name] :as source}]
                           (let [parsed (parse-path-spec path)
                                 metadata (dissoc source :path :name)]
                             (cond-> parsed
                               (seq metadata) (assoc :base-metadata metadata)
                               name (assoc-in [:base-metadata :name] name))))
                         sources))
    true (assoc :description (or description default-search-description))))
