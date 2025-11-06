(ns mcp-vector-search.watch-test
  (:require
    [babashka.fs :as fs]
    [clojure.test :refer [deftest testing is]]
    [hawk.core :as hawk]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.watch :as watch])
  (:import
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)
    (java.io
      File)))


(deftest watch-configuration-test
  ;; Test that watch configuration is properly handled in config processing

  (testing "process-config"

    (testing "defaults :watch? to false when not specified"
      (let [cfg {:sources [{:path "/docs/*.md"}]}
            result (config/process-config cfg)]
        (is (false? (:watch? result)))
        (is (false? (-> result :path-specs first :watch?)))))

    (testing "applies global :watch? to all sources"
      (let [cfg {:watch? true
                 :sources [{:path "/docs/*.md"}
                           {:path "/src/*.clj"}]}
            result (config/process-config cfg)]
        (is (true? (:watch? result)))
        (is (every? :watch? (:path-specs result)))))

    (testing "per-source :watch? overrides global default"
      (let [cfg {:watch? true
                 :sources [{:path "/docs/*.md" :watch? false}
                           {:path "/src/*.clj"}]}
            result (config/process-config cfg)]
        (is (true? (:watch? result)))
        (is (false? (-> result :path-specs first :watch?)))
        (is (true? (-> result :path-specs second :watch?)))))

    (testing "explicit false overrides global true"
      (let [cfg {:watch? true
                 :sources [{:path "/docs/*.md" :watch? false}]}
            result (config/process-config cfg)]
        (is (false? (-> result :path-specs first :watch?)))))

    (testing "does not include :watch? in metadata"
      (let [cfg {:sources [{:path "/docs/*.md" :watch? true}]}
            result (config/process-config cfg)]
        (is (not (contains? (-> result :path-specs first :base-metadata) :watch?)))))))


(deftest path-matching-test
  ;; Test that file paths are correctly matched against path specs

  (testing "matches-path-spec?"

    (testing "matches literal paths"
      (let [segments [{:type :literal :value "/docs/readme.md"}]]
        (is (true? (#'watch/matches-path-spec? "/docs/readme.md" segments)))
        (is (false? (#'watch/matches-path-spec? "/docs/other.md" segments)))))

    (testing "matches single-level globs"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :glob :pattern "*"}
                      {:type :literal :value ".md"}]]
        (is (true? (#'watch/matches-path-spec? "/docs/readme.md" segments)))
        (is (true? (#'watch/matches-path-spec? "/docs/guide.md" segments)))
        (is (false? (#'watch/matches-path-spec? "/docs/sub/readme.md" segments)))))

    (testing "matches recursive globs"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :glob :pattern "**"}
                      {:type :literal :value ".md"}]]
        (is (true? (#'watch/matches-path-spec? "/docs/readme.md" segments)))
        (is (true? (#'watch/matches-path-spec? "/docs/sub/guide.md" segments)))
        (is (true? (#'watch/matches-path-spec? "/docs/a/b/c/deep.md" segments)))
        (is (false? (#'watch/matches-path-spec? "/docs/readme.txt" segments)))))

    (testing "matches patterns with captures"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :capture :name "version" :pattern "v[0-9]+"}
                      {:type :literal :value "/"}
                      {:type :glob :pattern "*"}
                      {:type :literal :value ".md"}]]
        (is (true? (#'watch/matches-path-spec? "/docs/v1/readme.md" segments)))
        (is (true? (#'watch/matches-path-spec? "/docs/v2/guide.md" segments)))
        (is (false? (#'watch/matches-path-spec? "/docs/latest/readme.md" segments)))))))


(deftest recursive-watch-detection-test
  ;; Test detection of whether watching should be recursive

  (testing "should-watch-recursively?"

    (testing "detects recursive glob after base path"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :glob :pattern "**"}
                      {:type :literal :value ".md"}]
            base-path "/docs/"]
        (is (true? (#'watch/should-watch-recursively? segments base-path)))))

    (testing "returns false for single-level globs only"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :glob :pattern "*"}
                      {:type :literal :value ".md"}]
            base-path "/docs/"]
        (is (false? (#'watch/should-watch-recursively? segments base-path)))))

    (testing "returns false for literal-only paths"
      (let [segments [{:type :literal :value "/docs/readme.md"}]
            base-path "/docs/readme.md"]
        (is (false? (#'watch/should-watch-recursively? segments base-path)))))

    (testing "detects ** in middle of pattern"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :capture :name "version" :pattern "v[0-9]+"}
                      {:type :literal :value "/"}
                      {:type :glob :pattern "**"}
                      {:type :literal :value "/"}
                      {:type :glob :pattern "*"}
                      {:type :literal :value ".md"}]
            base-path "/docs/"]
        (is (true? (#'watch/should-watch-recursively? segments base-path)))))))


(deftest watch-event-firing-test
  ;; Test that watch events actually fire when files are created/modified/deleted

  (testing "watch fires on file creation"
    (let [temp-dir (fs/create-temp-dir)
          event-promise (promise)
          system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values (atom {})})
          config {:watch? true
                  :sources [{:path (str (fs/file temp-dir "*.md"))}]}
          parsed-config (config/process-config config)]

      (try
        ;; Override debounce-event to deliver the promise when called
        (with-redefs [watch/debounce-event
                      (fn [_ path event _]
                        (deliver event-promise {:path path :event event}))]

          ;; Start the watch
          (let [watchers (watch/start-watches system parsed-config)]

            (try
              ;; Create a file after a small delay to ensure watch is active
              (Thread/sleep 100)
              (let [test-file (fs/file temp-dir "test.md")]
                (spit test-file "Test content"))

              ;; Wait for the event with a 5 second timeout
              (let [result (deref event-promise 5000 :timeout)]
                (is (not= :timeout result) "Watch event should fire within 5 seconds")
                (when (not= :timeout result)
                  (is (string? (:path result)) "Event should have a path")
                  (is (map? (:event result)) "Event should be a map")
                  (is (contains? (:event result) :kind) "Event should have a :kind")))

              (finally
                (watch/stop-watches watchers)))))

        (finally
          (fs/delete-tree temp-dir)))))

  (testing "watch fires on file modification"
    (let [temp-dir (fs/create-temp-dir)
          test-file (fs/file temp-dir "test.md")
          _ (spit test-file "Initial content")
          event-promise (promise)
          system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values (atom {})})
          config {:watch? true
                  :sources [{:path (str (fs/file temp-dir "*.md"))}]}
          parsed-config (config/process-config config)]

      (try
        ;; Override debounce-event to deliver the promise when called
        (with-redefs [watch/debounce-event
                      (fn [_ path event _]
                        (when (= :modify (:kind event))
                          (deliver event-promise {:path path :event event})))]

          ;; Start the watch
          (let [watchers (watch/start-watches system parsed-config)]

            (try
              ;; Modify the file after a small delay
              (Thread/sleep 100)
              (spit test-file "Modified content")

              ;; Wait for the event with a 5 second timeout
              (let [result (deref event-promise 5000 :timeout)]
                (is (not= :timeout result) "Watch event should fire on modification within 5 seconds")
                (when (not= :timeout result)
                  (is (= :modify (get-in result [:event :kind])) "Event kind should be :modify")))

              (finally
                (watch/stop-watches watchers)))))

        (finally
          (fs/delete-tree temp-dir)))))

  (testing "watch filter and handler are called"
    (let [temp-dir (fs/create-temp-dir)
          filter-called (atom [])
          handler-called (atom [])
          system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values (atom {})})
          config {:watch? true
                  :sources [{:path (str (fs/file temp-dir "*.md"))}]}
          parsed-config (config/process-config config)]

      (try
        ;; Track filter and handler calls
        (let [original-watch! hawk/watch!]
          (with-redefs [hawk/watch!
                        (fn [watch-specs]
                          ;; Wrap the filter and handler to track calls
                          (let [spec (first watch-specs)
                                original-filter (:filter spec)
                                original-handler (:handler spec)
                                wrapped-spec (-> spec
                                                 (assoc :filter
                                                        (fn [ctx event]
                                                          (let [result (original-filter ctx event)]
                                                            (swap! filter-called conj
                                                                   {:file (.getPath (:file event))
                                                                    :result result})
                                                            result)))
                                                 (assoc :handler
                                                        (fn [ctx event]
                                                          (swap! handler-called conj
                                                                 {:file (.getPath (:file event))})
                                                          (original-handler ctx event))))]
                            (original-watch! [wrapped-spec])))]

            ;; Start the watch
            (let [watchers (watch/start-watches system parsed-config)]

              (try
                ;; Give watch time to start
                (Thread/sleep 200)

                ;; Create a file
                (let [test-file (fs/file temp-dir "test.md")]
                  (spit test-file "Test content"))

                ;; Wait for processing
                (Thread/sleep 1000)

                ;; Check if filter was called
                (is (pos? (count @filter-called))
                    (str "Filter should be called. Filter calls: " @filter-called))

                ;; Check if any filter calls returned true
                (let [accepted (filter :result @filter-called)]
                  (is (pos? (count accepted))
                      (str "At least one file should pass filter. Accepted: " accepted)))

                ;; Check if handler was called
                (is (pos? (count @handler-called))
                    (str "Handler should be called. Handler calls: " @handler-called))

                (finally
                  (watch/stop-watches watchers))))))

        (finally
          (fs/delete-tree temp-dir))))))


(deftest classpath-source-filtering-test
  ;; Test that classpath sources are excluded from file watching

  (testing "start-watches excludes classpath sources"
    (let [temp-dir (fs/create-temp-dir)
          system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values (atom {})})
          config {:watch? true
                  :sources [{:path (str (fs/file temp-dir "*.md"))}
                            {:class-path "docs/**/*.md"}]}
          parsed-config (config/process-config config)]

      (try
        (let [watchers (watch/start-watches system parsed-config)]
          (try
            ;; Only one watcher should be created (filesystem source only)
            (is (= 1 (count watchers)))

            (finally
              (watch/stop-watches watchers))))

        (finally
          (fs/delete-tree temp-dir)))))

  (testing "start-watches handles mixed configs correctly"
    (let [temp-dir (fs/create-temp-dir)
          system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values {}
                        :watch-stats {:enabled? false
                                      :sources []
                                      :events {:created 0
                                               :modified 0
                                               :deleted 0
                                               :last-event-at nil}
                                      :debounce {:queued 0
                                                 :processed 0}}})
          config {:watch? true
                  :sources [{:path (str (fs/file temp-dir "*.md"))}
                            {:class-path "docs/**/*.md"}
                            {:class-path "api/**/*.md"}]}
          parsed-config (config/process-config config)]

      (try
        (let [watchers (watch/start-watches system parsed-config)]
          (try
            ;; Verify only filesystem source is watched
            (is (= 1 (count (:sources (:watch-stats @system)))))
            (is (true? (-> (:watch-stats @system) :sources first :watching?)))

            (finally
              (watch/stop-watches watchers))))

        (finally
          (fs/delete-tree temp-dir)))))

  (testing "start-watches returns empty vector when all sources are classpath"
    (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                  :embedding-store (InMemoryEmbeddingStore.)
                  :metadata-values (atom {})}
          config {:watch? true
                  :sources [{:class-path "docs/**/*.md"}
                            {:class-path "api/**/*.md"}]}
          parsed-config (config/process-config config)
          watchers (watch/start-watches system parsed-config)]

      ;; No watchers should be created
      (is (empty? watchers)))))


(deftest watch-statistics-test
  ;; Test that watch statistics are properly tracked during watch operations

  (testing "start-watches initializes watch statistics"
    (let [temp-dir (fs/create-temp-dir)
          system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values {}
                        :watch-stats {:enabled? false
                                      :sources []
                                      :events {:created 0
                                               :modified 0
                                               :deleted 0
                                               :last-event-at nil}
                                      :debounce {:queued 0
                                                 :processed 0}}})
          config {:watch? true
                  :sources [{:path (str (fs/file temp-dir "*.md"))}]}
          parsed-config (config/process-config config)]

      (try
        (let [watchers (watch/start-watches system parsed-config)]
          (try
            ;; Verify watch-stats was updated
            (is (true? (:enabled? (:watch-stats @system))))
            (is (= 1 (count (:sources (:watch-stats @system)))))
            (is (true? (-> (:watch-stats @system) :sources first :watching?)))

            (finally
              (watch/stop-watches watchers))))

        (finally
          (fs/delete-tree temp-dir)))))

  (testing "debounce-event tracks queued events and timestamp"
    (let [system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values {}
                        :watch-stats {:enabled? true
                                      :sources []
                                      :events {:created 0
                                               :modified 0
                                               :deleted 0
                                               :last-event-at nil}
                                      :debounce {:queued 0
                                                 :processed 0}}})
          path-spec {:segments [{:type :literal :value "/test/"}
                                {:type :glob :pattern "*"}
                                {:type :literal :value ".md"}]
                     :base-metadata {}
                     :ingest :whole-document}]

      ;; Call debounce-event directly
      (#'watch/debounce-event system "/test/file.md" {:kind :create} path-spec)

      ;; Verify statistics were updated
      (is (= 1 (get-in (:watch-stats @system) [:debounce :queued])))
      (is (some? (get-in (:watch-stats @system) [:events :last-event-at])))))

  (testing "process-pending-events tracks event types and processed count"
    (let [temp-dir (fs/create-temp-dir)
          test-file (fs/file temp-dir "test.md")
          _ (spit test-file "Test content")
          system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values {}
                        :watch-stats {:enabled? true
                                      :sources []
                                      :events {:created 0
                                               :modified 0
                                               :deleted 0
                                               :last-event-at nil}
                                      :debounce {:queued 0
                                                 :processed 0}}})
          path-str (str test-file)
          path-spec {:segments (let [parsed (config/parse-path-spec path-str)]
                                 (:segments parsed))
                     :base-metadata {}
                     :ingest :whole-document}]

      (try
        ;; Simulate create event
        (reset! watch/debounce-state
                {path-str {:event {:kind :create :file test-file}
                           :path-spec path-spec}})
        (#'watch/process-pending-events system)

        ;; Verify create event was tracked
        (is (= 1 (get-in (:watch-stats @system) [:events :created])))
        (is (= 0 (get-in (:watch-stats @system) [:events :modified])))
        (is (= 0 (get-in (:watch-stats @system) [:events :deleted])))
        (is (= 1 (get-in (:watch-stats @system) [:debounce :processed])))

        ;; Simulate modify event
        (spit test-file "Modified content")
        (reset! watch/debounce-state
                {path-str {:event {:kind :modify :file test-file}
                           :path-spec path-spec}})
        (#'watch/process-pending-events system)

        ;; Verify modify event was tracked
        (is (= 1 (get-in (:watch-stats @system) [:events :created])))
        (is (= 1 (get-in (:watch-stats @system) [:events :modified])))
        (is (= 0 (get-in (:watch-stats @system) [:events :deleted])))
        (is (= 2 (get-in (:watch-stats @system) [:debounce :processed])))

        ;; Simulate delete event
        (reset! watch/debounce-state
                {path-str {:event {:kind :delete :file test-file}
                           :path-spec path-spec}})
        (#'watch/process-pending-events system)

        ;; Verify delete event was tracked
        (is (= 1 (get-in (:watch-stats @system) [:events :created])))
        (is (= 1 (get-in (:watch-stats @system) [:events :modified])))
        (is (= 1 (get-in (:watch-stats @system) [:events :deleted])))
        (is (= 3 (get-in (:watch-stats @system) [:debounce :processed])))

        (finally
          (fs/delete-tree temp-dir))))))
