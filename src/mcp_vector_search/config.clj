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

(defn- read-edn
  "Read and parse an EDN configuration from a file path or URL.
  Returns the parsed configuration map."
  [source]
  (-> source
      slurp
      edn/read-string))

(defn- find-config-location
  "Find config file location, checking classpath then filesystem.
  Returns URL (classpath) or path string (filesystem), or nil if not found."
  []
  (or
    ;; Try classpath resource
    (io/resource ".mcp-vector-search/config.edn")
    ;; Try project directory
    (let [project-config (io/file ".mcp-vector-search/config.edn")]
      (when (.exists project-config)
        (.getPath project-config)))
    ;; Try home directory
    (let [home-config (io/file (System/getProperty "user.home") ".mcp-vector-search/config.edn")]
      (when (.exists home-config)
        (.getPath home-config)))))

(defn load-config
  "Load configuration from specified path or default locations.

  When config-path is the default path, searches in order:
  1. Classpath resource: .mcp-vector-search/config.edn
  2. Project directory: .mcp-vector-search/config.edn
  3. Home directory: ~/.mcp-vector-search/config.edn

  When config-path is explicitly provided, loads from filesystem only.

  Returns the parsed configuration map.
  Throws ex-info if no configuration found."
  ([]
   (load-config ".mcp-vector-search/config.edn"))
  ([config-path]
   (let [is-default? (= config-path ".mcp-vector-search/config.edn")
         location (if is-default?
                    (find-config-location)
                    (when (.exists (io/file config-path))
                      config-path))]
     (if location
       (read-edn location)
       (throw (ex-info "No configuration file found"
                       {:searched (if is-default?
                                    ["classpath:.mcp-vector-search/config.edn"
                                     ".mcp-vector-search/config.edn"
                                     "~/.mcp-vector-search/config.edn"]
                                    [config-path])}))))))

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


(defn- validate-source
  "Validate source configuration.
  Validates that exactly one of :path or :class-path is present.
  Validates that :single-segment ingest has required strategy keys.
  Throws ex-info if validation fails."
  [{:keys [path class-path ingest embedding content-strategy] :as source}]
  ;; Validate path/class-path presence
  (when (and path class-path)
    (throw (ex-info "Source cannot have both :path and :class-path"
                    {:source source})))
  (when (and (nil? path) (nil? class-path))
    (throw (ex-info "Source must have either :path or :class-path"
                    {:source source})))

  ;; Validate single-segment strategy
  (when (= :single-segment ingest)
    (when-not embedding
      (throw (ex-info "Missing :embedding key for :single-segment ingest"
                      {:source source
                       :path (or path class-path)})))
    (when-not content-strategy
      (throw (ex-info "Missing :content-strategy key for :single-segment ingest"
                      {:source source
                       :path (or path class-path)})))))

(defn process-config
  "Process user config into internal format.

  Converts :sources (with raw :path or :class-path strings) to :path-specs
  (with parsed segments).

  Each source entry should have:
  - :path - raw filesystem path spec string (mutually exclusive with :class-path)
  - :class-path - raw classpath path spec string (mutually exclusive with :path)
  - :name (optional) - source name
  - :ingest (optional) - ingest pipeline strategy (:whole-document, :namespace-doc, :file-path, :chunked, :single-segment), defaults to :whole-document
  - :watch? (optional) - enable file watching, defaults to global :watch? or false
  - additional keys become base-metadata

  For :single-segment ingest:
  - :embedding (required) - embedding strategy keyword
  - :content-strategy (required) - content extraction strategy keyword

  Global config keys:
  - :watch? - global default for file watching (default: false)
  - :description - search tool description

  Adds :description with default if not provided.
  Adds :ingest, :watch?, and :source-type to each path-spec.
  Validates source configurations.

  Returns a config map with :path-specs ready for ingestion."
  [{:keys [sources description watch?] :as _config}]
  (let [global-watch? (boolean watch?)]
    (cond-> {}
      sources (assoc :path-specs
                     (mapv (fn [{:keys [path class-path name ingest watch?] :as source}]
                             (validate-source source)
                             (let [path-string (or path class-path)
                                   source-type (if path :filesystem :classpath)
                                   parsed (parse-path-spec path-string)
                                   strategy (or ingest :whole-document)
                                   metadata (dissoc source :path :class-path :name :ingest :watch? :embedding :content-strategy)]
                               (cond-> parsed
                                 (seq metadata) (assoc :base-metadata metadata)
                                 name (assoc-in [:base-metadata :name] name)
                                 true (assoc :ingest strategy)
                                 true (assoc :source-type source-type)
                                 true (assoc :watch? (if (nil? watch?) global-watch? (boolean watch?))))))
                           sources))
      true (assoc :description (or description default-search-description))
      true (assoc :watch? global-watch?))))
