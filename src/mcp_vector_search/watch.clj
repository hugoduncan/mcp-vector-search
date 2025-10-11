(ns mcp-vector-search.watch
  "File watching with debouncing for automatic re-indexing.

  ## Responsibilities
  Monitors configured file paths for changes and automatically re-indexes
  modified documents. Handles file creation, modification, and deletion events.
  Provides lifecycle management for watch resources.

  ## Implementation Notes
  Uses hawk library for filesystem watching. Events are debounced (500ms) to
  avoid excessive re-indexing when multiple rapid changes occur. Path
  normalization handles symlinks (e.g., /var -> /private/var on macOS).
  Modified files have old embeddings removed by `:doc-id` before re-indexing."
  (:refer-clojure :exclude [file?])
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [hawk.core :as hawk]
    [mcp-clj.mcp-server.logging :as logging]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest])
  (:import
    (dev.langchain4j.store.embedding
      EmbeddingStore)
    (java.io
      File)
    (java.util
      Timer)
    (java.util.regex
      Matcher
      Pattern)))

;; Debouncing

(defonce debounce-state (atom {}))
(defonce debounce-timer (atom nil))

(def debounce-ms 500)

(defn- build-pattern
  "Build a regex pattern from segments with named groups."
  [segments]
  (let [pattern-str
        (str/join
          (mapv
            (fn [{:keys [type] :as segment}]
              (case type
                :literal (Pattern/quote (:value segment))
                :glob    (case (:pattern segment)
                           "**" ".*?"
                           "*"  "[^/]*")
                :capture (str "(?<" (:name segment) ">" (:pattern segment) ")")))
            segments))]
    (Pattern/compile pattern-str)))

(defn- extract-captures
  "Extract named group captures from a regex matcher.
  Returns a map of capture name to captured value."
  [^Matcher matcher segments]
  (let [capture-names (keep #(when (= :capture (:type %))
                               (:name %))
                            segments)]
    (into {}
          (map (fn [^String name]
                 [(keyword name) (.group matcher name)])
               capture-names))))

(defn- log-if-server
  "Log a message if server is available in the system."
  [system level data]
  (when-let [server (:server system)]
    (case level
      :info (logging/info server data :logger "watch")
      :warn (logging/warn server data :logger "watch")
      :error (logging/error server data :logger "watch")
      :debug (logging/debug server data :logger "watch"))))

(defn- process-pending-events
  "Process all pending debounced events."
  [system]
  (let [[old-state _new-state] (swap-vals! debounce-state (constantly {}))
        events-to-process
        (reduce-kv
          (fn [acc path {:keys [event path-spec]}]
            (conj acc {:path path :event event :path-spec path-spec}))
          []
          old-state)]

    ;; Process each event
    (doseq [{:keys [path event path-spec]} events-to-process]
      (try
        (log-if-server system :info {:event event :path path})
        (let [kind (:kind event)]
          (cond
            (#{:create :modify} kind)
            (let [file (io/file path)
                  base-metadata (:base-metadata path-spec)
                  embedding (:embedding path-spec)
                  ingest-strategy (:ingest path-spec)
                  pattern (build-pattern (:segments path-spec))
                  matcher (re-matcher pattern path)]
              (if (.matches matcher)
                (let [captures (extract-captures matcher (:segments path-spec))
                      metadata (merge base-metadata captures)
                      file-map {:file file
                                :path path
                                :captures captures
                                :metadata metadata
                                :embedding embedding
                                :ingest ingest-strategy}]

                  (when (= kind :modify)
                    (log-if-server
                      system
                      :info
                      {:event "file-modified" :path path})
                    ;; Remove old version before re-adding
                    (let [^EmbeddingStore embedding-store (:embedding-store system)]
                      (.removeAll embedding-store (java.util.Arrays/asList (into-array String [path])))))
                  (when (= kind :create)
                    (log-if-server
                      system
                      :info
                      {:event "file-created" :path path}))
                  (let [result (ingest/ingest-file system file-map)]
                    (when (:error result)
                      (log-if-server system :error {:event "ingest-failed"
                                                    :path path
                                                    :error (:error result)}))))
                ;; Pattern didn't match - this shouldn't happen if filter is correct
                (log-if-server system :warn {:event "file-not-matched"
                                             :path path
                                             :pattern (str pattern)})))

            (= kind :delete)
            (do
              (log-if-server system :info {:event "file-deleted" :path path})
              (let [^EmbeddingStore embedding-store (:embedding-store system)]
                (.removeAll embedding-store (java.util.Arrays/asList (into-array String [path])))))))
        (catch Exception e
          (log-if-server system :error {:event "watch-error"
                                        :path path
                                        :error (.getMessage e)})
          (.printStackTrace e))))))

(defn- schedule-debounce-flush
  "Schedule a flush of pending events after debounce period."
  [system]
  ;; Only schedule if not already scheduled
  (when-not @debounce-timer
    (let [timer (java.util.Timer. true)]
      (reset! debounce-timer timer)
      (.schedule timer
                 (proxy [java.util.TimerTask] []
                   (run
                     []
                     (try
                       (process-pending-events system)
                       (finally
                         (reset! debounce-timer nil)
                         ;; Reschedule if more events arrived
                         (when (seq @debounce-state)
                           (schedule-debounce-flush system))))))
                 (long debounce-ms)))))

(defn- debounce-event
  "Add event to debounce state and schedule processing."
  [system path event path-spec]
  (swap! debounce-state
         assoc
         path
         {:event event
          :path-spec path-spec})
  (schedule-debounce-flush system))

;; Watch setup

(defn- should-watch-recursively?
  "Determine if watching should be recursive based on path-spec segments."
  [segments base-path]
  (let [remaining (drop-while #(= :literal (:type %)) segments)]
    (boolean (some #(and (= :glob (:type %))
                         (= "**" (:pattern %)))
                   remaining))))

(defn- matches-path-spec?
  "Check if a file path matches the path spec pattern."
  [path segments]
  (let [pattern (build-pattern segments)
        matcher (re-matcher pattern path)]
    (.matches matcher)))

(defn- normalize-path
  "Normalize a path by resolving symlinks to get canonical path."
  [path]
  (try
    (.getCanonicalPath (io/file path))
    (catch Exception _
      path)))

(defn- setup-watch
  "Setup a watch for a single path-spec.
  Returns a hawk watcher handle."
  [system path-spec]
  (let [{:keys [base-path segments]} path-spec
        ;; Convert relative paths to absolute, then normalize to handle symlinks
        base-file-for-absolute (io/file base-path)
        absolute-base (if (.isAbsolute base-file-for-absolute)
                        base-path
                        (.getCanonicalPath base-file-for-absolute))
        ;; Normalize to handle symlinks (e.g., /var -> /private/var on macOS)
        normalized-base (normalize-path absolute-base)
        base-file (io/file normalized-base)
        watch-path (if (.isFile base-file)
                     (.getParent base-file)
                     normalized-base)
        ;; Build pattern using absolute normalized base
        ;; First, determine what the original literal prefix would be as absolute
        original-relative-base (str/join (map :value (take-while #(= :literal (:type %)) segments)))
        ;; Preserve trailing separator if present
        has-trailing-sep? (str/ends-with? original-relative-base "/")
        normalized-base-with-sep (if (and has-trailing-sep?
                                         (not (str/ends-with? normalized-base "/")))
                                   (str normalized-base "/")
                                   normalized-base)
        ;; Build the full pattern string from segments
        relative-pattern-str (str/join (mapv (fn [{:keys [type] :as segment}]
                                                (case type
                                                  :literal (:value segment)
                                                  :glob (case (:pattern segment)
                                                          "**" "**"
                                                          "*" "*")
                                                  :capture (str "(?<" (:name segment) ">" (:pattern segment) ")")))
                                              segments))
        ;; Replace the relative base with the absolute normalized base
        normalized-pattern (str/replace-first
                             relative-pattern-str
                             original-relative-base
                             normalized-base-with-sep)
        ;; Parse the normalized pattern back into segments
        normalized-segments (:segments (config/parse-path-spec normalized-pattern))
        ;; Create normalized path-spec for event processing
        normalized-path-spec (assoc path-spec :segments normalized-segments)
        recursive? (should-watch-recursively? normalized-segments normalized-base)]

    (try
      (log-if-server system :info {:event "watch-started"
                                   :path watch-path
                                   :recursive recursive?})
      (hawk/watch!
        [{:paths [watch-path]
          :filter (fn [ctx {:keys [file] :as event}]
                    (let [is-file? (hawk/file? ctx event)
                          file-path (normalize-path (.getPath ^File file))
                          matches? (matches-path-spec? file-path normalized-segments)
                          should-process? (and is-file? matches?)]
                      (when (and is-file? (not matches?))
                        (log-if-server system :info {:event "file-filtered-out"
                                                     :path file-path
                                                     :watch-path watch-path}))
                      should-process?))
          :handler (fn [ctx event]
                     (debounce-event system (normalize-path (.getPath ^File (:file event))) event normalized-path-spec)
                     ctx)}])
      (catch Exception e
        (log-if-server system :error {:event "watch-setup-failed"
                                      :path normalized-base
                                      :error (.getMessage e)})
        (binding [*out* *err*]
          (println "Failed to setup watch for" base-path ":" (.getMessage e)))
        nil))))

(defn start-watches
  "Start file watching for all path-specs with :watch? enabled.
  Takes a system map and parsed config with :path-specs.
  Returns a vector of hawk watcher handles."
  [system {:keys [path-specs watch?] :as _config}]
  (let [global-watch? (boolean watch?)
        watched-specs (filter #(get % :watch? global-watch?) path-specs)]
    (when (seq watched-specs)
      (into [] (keep #(setup-watch system %)) watched-specs))))

(defn stop-watches
  "Stop all file watches.
  Takes a vector of hawk watcher handles."
  [watchers]
  (doseq [watcher watchers]
    (try
      (hawk/stop! watcher)
      (catch Exception e
        (println "Error stopping watch:" (.getMessage e)))))

  ;; Cancel debounce timer
  (when-let [^Timer timer @debounce-timer]
    (.cancel timer)
    (reset! debounce-timer nil))

  ;; Clear debounce state
  (reset! debounce-state {}))
