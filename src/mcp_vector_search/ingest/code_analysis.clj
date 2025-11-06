(ns mcp-vector-search.ingest.code-analysis
  "Code analysis ingestion strategy using clj-kondo.

  Analyzes Clojure and Java source files using clj-kondo to extract code
  elements (vars, namespaces, classes, methods, fields, etc.). Creates one
  segment per code element with the docstring (or element name) for embedding
  and the complete clj-kondo analysis map for content.

  Configuration options (via metadata):
  - :visibility - :all (default) or :public-only
  - :element-types - Set of element types to include (if omitted, includes all)

  Metadata for each segment includes:
  - :element-type - Type of code element
  - :element-name - Qualified name
  - :language - Source language (clojure or java)
  - :namespace - Containing namespace/package (only included if present)
  - :visibility - Access level (public, private, protected)"
  (:require
    [clj-kondo.core :as clj-kondo]
    [clojure.string :as str]
    [mcp-vector-search.ingest.common :as common]))


(defn- run-analysis
  "Run clj-kondo analysis on a file path.

  Returns the analysis result map with keys like :var-definitions,
  :namespace-definitions, :java-class-definitions, :java-member-definitions,
  and :findings on success.

  Returns {:error exception-message} on failure."
  [path]
  (try
    (let [result (clj-kondo/run! {:lint [path]
                                  :config {:output {:analysis {:keywords true
                                                               :java-class-definitions true
                                                               :java-member-definitions true}}}})]
      (:analysis result))
    (catch Exception e
      {:error (.getMessage e)})))


(defn- element-type
  "Determine the element type keyword from analysis key and element data.

  For Java members, clj-kondo provides explicit type markers in the :flags set:
  - Constructors have :method flag but no :return-type
  - Methods have :method flag and :return-type
  - Fields have :field flag and :type"
  [analysis-key element]
  (case analysis-key
    :var-definitions (if (:macro element) :macro :var)
    :namespace-definitions :namespace
    :java-class-definitions :class
    :java-member-definitions
    (let [flags (:flags element #{})]
      (cond
        (and (contains? flags :method)
             (not (:return-type element)))
        :constructor

        (contains? flags :method) :method
        (contains? flags :field) :field
        ;; Fallback to field detection - should not occur with current clj-kondo
        (:return-type element) :method
        (:type element) :field
        :else nil))
    nil))


(defn- element-name
  "Build qualified name for an element."
  [analysis-key element]
  (case analysis-key
    :var-definitions (str (:ns element) "/" (:name element))
    :namespace-definitions (str (:name element))
    :java-class-definitions (:class element)
    :java-member-definitions (str (:class element) "." (:name element))
    nil))


(defn- embedding-text
  "Select text to use for embedding: docstring if present, otherwise element name.

  Empty or whitespace-only docstrings are treated as absent."
  [element element-name]
  (if-let [doc (:doc element)]
    (let [trimmed (str/trim doc)]
      (if (seq trimmed)
        doc
        element-name))
    element-name))


(defn- element-language
  "Determine language from analysis key."
  [analysis-key]
  (if (str/starts-with? (name analysis-key) "java")
    "java"
    "clojure"))


(defn- element-visibility
  "Determine visibility string from element data."
  [analysis-key element]
  (case analysis-key
    (:var-definitions :namespace-definitions)
    (if (:private element) "private" "public")

    (:java-class-definitions :java-member-definitions)
    (let [flags (:flags element #{})]
      (cond
        (contains? flags :private) "private"
        (contains? flags :protected) "protected"
        :else "public"))

    "public"))


(defn- public-element?
  "Check if element passes visibility filter."
  [analysis-key element visibility-config]
  (if (= visibility-config :public-only)
    (case analysis-key
      (:var-definitions :namespace-definitions)
      (not (:private element))

      (:java-class-definitions :java-member-definitions)
      (let [flags (:flags element #{})]
        (and (not (contains? flags :private))
             (not (contains? flags :protected))))

      true)
    true))


(defn- parse-config
  "Parse and validate configuration from metadata.

  Returns map with :visibility and :element-types keys.
  Throws ex-info for invalid configuration values."
  [metadata path]
  (let [visibility (get metadata :visibility :all)
        element-types (get metadata :element-types)]
    (when-not (#{:all :public-only} visibility)
      (throw (ex-info (str "Invalid :visibility for " path ": must be :all or :public-only")
                      {:visibility visibility :path path})))
    (when (and element-types (not (set? element-types)))
      (throw (ex-info (str "Invalid :element-types for " path ": must be a set")
                      {:element-types element-types :path path})))
    {:visibility visibility
     :element-types element-types}))


(defn- extract-elements
  "Extract all code elements from clj-kondo analysis results.

  Applies visibility and element-type filtering based on config.
  Returns sequence of maps with :type, :name, :data, :embedding-text,
  :language, :visibility, and :namespace."
  [analysis config]
  (when analysis
    (let [{:keys [visibility element-types]} config]
      (for [analysis-key [:var-definitions :namespace-definitions
                          :java-class-definitions :java-member-definitions]
            :let [elements (get analysis analysis-key)]
            :when elements
            element elements
            :when (public-element? analysis-key element visibility)
            :let [type (element-type analysis-key element)
                  name (element-name analysis-key element)
                  embed-text (embedding-text element name)
                  lang (element-language analysis-key)
                  vis (element-visibility analysis-key element)
                  ns (or (:ns element) (:package element))]
            :when (and type name)
            :when (or (nil? element-types) (contains? element-types type))]
        {:type type
         :name name
         :data element
         :embedding-text embed-text
         :language lang
         :visibility vis
         :namespace ns}))))


(defmethod common/process-document :code-analysis
  [_strategy path _content metadata]
  (let [config (parse-config metadata path)
        analysis (run-analysis path)]
    ;; Check for analysis errors
    (when (:error analysis)
      (throw (ex-info (str "clj-kondo analysis failed: " (:error analysis))
                      {:type :analysis-error
                       :path path
                       :error (:error analysis)})))
    (let [elements (extract-elements analysis config)
          file-id path]
      (map-indexed
        (fn [idx element]
          (let [segment-id (common/generate-segment-id file-id idx)
                text-to-embed (:embedding-text element)
                content-to-store (pr-str (:data element))
                enhanced-metadata (merge metadata
                                         {:element-type (name (:type element))
                                          :element-name (:name element)
                                          :language (:language element)
                                          :visibility (:visibility element)}
                                         (when-let [ns (:namespace element)]
                                           {:namespace (str ns)}))]
            (common/create-segment-descriptor file-id segment-id text-to-embed content-to-store enhanced-metadata)))
        elements))))
