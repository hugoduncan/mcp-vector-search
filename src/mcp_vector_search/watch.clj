(ns mcp-vector-search.watch
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [hawk.core :as hawk]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest])
  (:refer-clojure :exclude [file?])
  (:import
    (java.io
      File)
    (java.util.regex
      Pattern)))

;;; Debouncing

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
  [matcher segments]
  (let [capture-names (keep #(when (= :capture (:type %))
                               (:name %))
                            segments)]
    (into {}
          (map (fn [name]
                 [(keyword name) (.group matcher name)])
               capture-names))))

(defn- process-pending-events
  "Process all pending debounced events."
  [system]
  (let [events-to-process
        (reduce-kv
          (fn [acc path {:keys [event path-spec]}]
            (conj acc {:path path :event event :path-spec path-spec}))
          []
          @debounce-state)]

    ;; Clear all pending events
    (reset! debounce-state {})

    ;; Process each event
    (doseq [{:keys [path event path-spec]} events-to-process]
      (try
        (let [kind (:kind event)]
          (cond
            (or (contains? kind :create) (contains? kind :modify))
            (let [file (io/file path)
                  base-metadata (:base-metadata path-spec)
                  embedding (:embedding path-spec)
                  ingest-strategy (:ingest path-spec)
                  pattern (build-pattern (:segments path-spec))
                  matcher (re-matcher pattern path)]
              (when (.matches matcher)
                (let [captures (extract-captures matcher (:segments path-spec))
                      metadata (merge base-metadata captures)
                      file-map {:file file
                                :path path
                                :captures captures
                                :metadata metadata
                                :embedding embedding
                                :ingest ingest-strategy}]
                  (when (contains? kind :modify)
                    ;; Remove old version before re-adding
                    (let [embedding-store (:embedding-store system)]
                      (.removeAll embedding-store [path])))
                  (ingest/ingest-file system file-map))))

            (contains? kind :delete)
            (let [embedding-store (:embedding-store system)]
              (.removeAll embedding-store [path]))))
        (catch Exception e
          (println "Error processing watch event for" path ":" (.getMessage e)))))))

(defn- schedule-debounce-flush
  "Schedule a flush of pending events after debounce period."
  [system]
  ;; Only schedule if not already scheduled
  (when-not @debounce-timer
    (let [timer (java.util.Timer. true)]
      (reset! debounce-timer timer)
      (.schedule timer
                 (proxy [java.util.TimerTask] []
                   (run []
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

;;; Watch setup

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
        ;; Normalize base-path to handle symlinks (e.g., /var -> /private/var on macOS)
        normalized-base (normalize-path base-path)
        base-file (io/file normalized-base)
        watch-path (if (.isFile base-file)
                     (.getParent base-file)
                     normalized-base)
        ;; Build normalized pattern by substituting normalized base for original base
        original-base (str/join (map :value (take-while #(= :literal (:type %)) segments)))
        normalized-pattern (str/replace-first
                             (str/join (mapv (fn [{:keys [type] :as segment}]
                                               (case type
                                                 :literal (:value segment)
                                                 :glob (case (:pattern segment)
                                                         "**" "**"
                                                         "*" "*")
                                                 :capture (str "(?<" (:name segment) ">" (:pattern segment) ")")))
                                             segments))
                             original-base
                             normalized-base)
        ;; Parse the normalized pattern back into segments
        normalized-segments (:segments (config/parse-path-spec normalized-pattern))
        recursive? (should-watch-recursively? segments base-path)]

    (try
      (hawk/watch!
        [{:paths [watch-path]
          :filter (fn [ctx {:keys [file] :as event}]
                    (and (hawk/file? ctx event)
                         (matches-path-spec? (.getPath ^File file) normalized-segments)))
          :handler (fn [ctx event]
                     (debounce-event system (.getPath ^File (:file event)) event path-spec)
                     ctx)}])
      (catch Exception e
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
  (when-let [timer @debounce-timer]
    (.cancel timer)
    (reset! debounce-timer nil))

  ;; Clear debounce state
  (reset! debounce-state {}))
