(ns mcp-vector-search.resources
  "MCP resource definitions for ingestion status and statistics.

  ## Responsibilities
  Exposes read-only resources via MCP protocol to help users debug their
  configuration. Provides five resources covering overall status, per-source
  statistics, error tracking, path metadata captures, and watch event statistics.

  ## Resource URIs
  - `ingestion://status` - Overall ingestion summary
  - `ingestion://stats` - Per-source statistics
  - `ingestion://failures` - Last 20 errors (bounded queue)
  - `ingestion://metadata` - Path segment captured metadata
  - `ingestion://watch-stats` - File watching statistics

  ## Implementation Notes
  Resources read from system state atoms and return JSON-formatted data.
  All resource implementations follow the mcp-clj pattern, returning maps
  with `:contents` vector and `:isError` boolean."
  (:require
    [clojure.data.json :as json]))

;;;  Helper Functions

(defn- format-json-response
  "Format data as a JSON resource response.

  Parameters:
  - uri: Resource URI string (e.g., \"ingestion://status\")
  - data: Clojure data structure to serialize as JSON

  Returns:
  Map with `:contents` vector containing single resource entry and `:isError` false.
  Contents entry has `:uri`, `:mimeType` (\"application/json\"), and `:text` (JSON string).

  Error Handling:
  None - assumes json/write-str will succeed. Callers should wrap in try/catch."
  [uri data]
  {:contents [{:uri uri
               :mimeType "application/json"
               :text (json/write-str data)}]
   :isError false})

(defn- format-error-response
  "Format error message as a resource response.

  Parameters:
  - uri: Resource URI string (e.g., \"ingestion://status\")
  - message: Error message string to return to client

  Returns:
  Map with `:contents` vector containing single resource entry and `:isError` true.
  Contents entry has `:uri`, `:mimeType` (\"text/plain\"), and `:text` (error message).

  Error Handling:
  None - this function is used to format errors from other functions."
  [uri message]
  {:contents [{:uri uri
               :mimeType "text/plain"
               :text message}]
   :isError true})

;;;  Resource Implementations

(defn- ingestion-status-impl
  "Implementation for ingestion://status resource.

  Parameters:
  - system: Atom containing system state with `:ingestion-stats` map
  - _uri: Resource URI (unused, provided for consistency with other impls)

  Returns:
  JSON resource with overall ingestion summary including:
  - `:total-documents` - Total number of documents processed
  - `:total-segments` - Total number of segments created
  - `:total-errors` - Total number of errors encountered
  - `:last-ingestion-at` - Timestamp of last ingestion (string or nil)

  Error Handling:
  Catches all exceptions and returns error response with exception message."
  [system _uri]
  (try
    (let [{:keys [total-documents total-segments total-errors last-ingestion-at]}
          (:ingestion-stats @system)]
      (format-json-response
        "ingestion://status"
        {:total-documents total-documents
         :total-segments total-segments
         :total-errors total-errors
         :last-ingestion-at last-ingestion-at}))
    (catch Exception e
      (format-error-response
        "ingestion://status"
        (str "Failed to read ingestion status: " (.getMessage e))))))

(defn- ingestion-stats-impl
  "Implementation for ingestion://stats resource.

  Parameters:
  - system: Atom containing system state with `:ingestion-stats` map
  - _uri: Resource URI (unused, provided for consistency with other impls)

  Returns:
  JSON resource with per-source statistics including:
  - `:sources` - Vector of source statistics, each containing:
    - `:path-spec` - Path specification that matched files
    - `:files-matched` - Number of files matched by path spec
    - `:files-processed` - Number of files successfully processed
    - `:segments-created` - Number of segments created from source
    - `:errors` - Number of errors encountered for this source

  Error Handling:
  Catches all exceptions and returns error response with exception message."
  [system _uri]
  (try
    (let [{:keys [sources]} (:ingestion-stats @system)]
      (format-json-response
        "ingestion://stats"
        {:sources (mapv (fn [source]
                          {:path-spec (:path-spec source)
                           :files-matched (:files-matched source)
                           :files-processed (:files-processed source)
                           :segments-created (:segments-created source)
                           :errors (:errors source)})
                        sources)}))
    (catch Exception e
      (format-error-response
        "ingestion://stats"
        (str "Failed to read ingestion statistics: " (.getMessage e))))))

(defn- ingestion-failures-impl
  "Implementation for ingestion://failures resource.

  Parameters:
  - system: Atom containing system state with `:ingestion-failures` vector
  - _uri: Resource URI (unused, provided for consistency with other impls)

  Returns:
  JSON resource with bounded queue (max 20) of failure records, each containing:
  - `:file-path` - Path to file that failed to process
  - `:error-type` - Keyword indicating error type (:read-error, :parse-error, etc.)
  - `:message` - Error message string
  - `:source-path` - Path spec that matched this file
  - `:timestamp` - ISO-8601 timestamp string when error occurred

  Error Handling:
  Catches all exceptions and returns error response with exception message."
  [system _uri]
  (try
    (let [failures (:ingestion-failures @system)]
      (format-json-response
        "ingestion://failures"
        {:failures (mapv (fn [failure]
                           {:file-path (:file-path failure)
                            :error-type (:error-type failure)
                            :message (:message failure)
                            :source-path (:source-path failure)
                            :timestamp (str (:timestamp failure))})
                         failures)}))
    (catch Exception e
      (format-error-response
        "ingestion://failures"
        (str "Failed to read ingestion failures: " (.getMessage e))))))

(defn- path-metadata-impl
  "Implementation for ingestion://metadata resource.

  Parameters:
  - system: Atom containing system state with `:path-captures` map
  - _uri: Resource URI (unused, provided for consistency with other impls)

  Returns:
  JSON resource with path segment capture metadata including:
  - `:path-specs` - Vector of path specs with captures, each containing:
    - `:path` - Path specification string (e.g., \"/docs/(?<category>[^/]+)/*.md\")
    - `:captures` - Map of capture name to set of captured values

  Error Handling:
  Catches all exceptions and returns error response with exception message."
  [system _uri]
  (try
    (let [{:keys [path-specs]} (:path-captures @system)]
      (format-json-response
        "ingestion://metadata"
        {:path-specs (mapv (fn [spec]
                             {:path (:path spec)
                              :captures (:captures spec)})
                           path-specs)}))
    (catch Exception e
      (format-error-response
        "ingestion://metadata"
        (str "Failed to read path metadata: " (.getMessage e))))))

(defn- watch-stats-impl
  "Implementation for ingestion://watch-stats resource.

  Parameters:
  - system: Atom containing system state with `:watch-stats` map
  - _uri: Resource URI (unused, provided for consistency with other impls)

  Returns:
  JSON resource with file watching statistics including:
  - `:enabled` - Boolean indicating if watching is enabled globally
  - `:sources` - Vector of source watch configurations, each containing:
    - `:path` - Path specification being watched
    - `:watching` - Boolean indicating if this source is being watched
  - `:events` - Map of event statistics:
    - `:created` - Count of file creation events
    - `:modified` - Count of file modification events
    - `:deleted` - Count of file deletion events
    - `:last-event-at` - ISO-8601 timestamp string of last event
  - `:debounce` - Map of debounce statistics:
    - `:queued` - Count of events queued for debouncing
    - `:processed` - Count of events processed after debouncing

  Error Handling:
  Catches all exceptions and returns error response with exception message."
  [system _uri]
  (try
    (let [{:keys [enabled? sources events debounce]} (:watch-stats @system)]
      (format-json-response
        "ingestion://watch-stats"
        {:enabled enabled?
         :sources (mapv (fn [source]
                          {:path (:path source)
                           :watching (:watching? source)})
                        sources)
         :events {:created (:created events)
                  :modified (:modified events)
                  :deleted (:deleted events)
                  :last-event-at (str (:last-event-at events))}
         :debounce {:queued (:queued debounce)
                    :processed (:processed debounce)}}))
    (catch Exception e
      (format-error-response
        "ingestion://watch-stats"
        (str "Failed to read watch statistics: " (.getMessage e))))))

;;;  Public API

(defn resource-definitions
  "Create resource definitions for the MCP server.
  Takes a system atom containing ingestion state.
  Returns a map of resource name to resource definition."
  [system]
  {"ingestion-status"
   {:name "Ingestion Status"
    :uri "ingestion://status"
    :mime-type "application/json"
    :description "Overall ingestion summary with total documents, segments, and errors"
    :implementation (fn [_context uri]
                      (ingestion-status-impl system uri))}

   "ingestion-stats"
   {:name "Ingestion Statistics"
    :uri "ingestion://stats"
    :mime-type "application/json"
    :description "Per-source ingestion statistics including files matched, processed, and errors"
    :implementation (fn [_context uri]
                      (ingestion-stats-impl system uri))}

   "ingestion-failures"
   {:name "Ingestion Failures"
    :uri "ingestion://failures"
    :mime-type "application/json"
    :description "Last 20 ingestion errors with file paths, error types, and timestamps"
    :implementation (fn [_context uri]
                      (ingestion-failures-impl system uri))}

   "path-metadata"
   {:name "Path Metadata"
    :uri "ingestion://metadata"
    :mime-type "application/json"
    :description "Metadata extracted from path segment captures showing which path specs have captures and example values"
    :implementation (fn [_context uri]
                      (path-metadata-impl system uri))}

   "watch-stats"
   {:name "Watch Statistics"
    :uri "ingestion://watch-stats"
    :mime-type "application/json"
    :description "File watching statistics including event counts, per-source watch status, and debounce metrics"
    :implementation (fn [_context uri]
                      (watch-stats-impl system uri))}})
