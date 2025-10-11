(ns mcp-vector-search.config
  "Configuration reading and path specification parsing.

  ## Responsibilities
  Reads EDN configuration files and transforms them into internal format.
  Parses path specifications containing globs, literals, and named captures
  into structured segments for filesystem matching and metadata extraction.

  ## Implementation Notes
  Path spec parsing algorithm processes strings left-to-right, identifying
  named captures `(?<name>pattern)`, globs (`*`, `**`), and literal text.
  Calculates base paths (literal prefix) to determine filesystem walk roots.
  See doc/path-spec.md for formal specification."
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
  (loop [^String remaining path-str
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
        (let [indices      (keep #(let [^String s %
                                        idx (.indexOf remaining s)]
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
  - :embedding (optional) - embedding strategy, defaults to :whole-document
  - :ingest (optional) - ingest strategy, defaults to :whole-document
  - :watch? (optional) - enable file watching, defaults to global :watch? or false
  - additional keys become base-metadata

  Global config keys:
  - :watch? - global default for file watching (default: false)
  - :description - search tool description

  Adds :description with default if not provided.
  Adds :embedding, :ingest, and :watch? defaults to each path-spec.

  Returns a config map with :path-specs ready for ingestion."
  [{:keys [sources description watch?] :as _config}]
  (let [global-watch? (boolean watch?)]
    (cond-> {}
      sources (assoc :path-specs
                     (mapv (fn [{:keys [path name embedding ingest watch?] :as source}]
                             (let [parsed (parse-path-spec path)
                                   metadata (dissoc source :path :name :embedding :ingest :watch?)]
                               (cond-> parsed
                                 (seq metadata) (assoc :base-metadata metadata)
                                 name (assoc-in [:base-metadata :name] name)
                                 true (assoc :embedding (or embedding :whole-document))
                                 true (assoc :ingest (or ingest :whole-document))
                                 true (assoc :watch? (if (nil? watch?) global-watch? (boolean watch?))))))
                           sources))
      true (assoc :description (or description default-search-description))
      true (assoc :watch? global-watch?))))
