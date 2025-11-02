# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

An MCP (Model Context Protocol) server that indexes documents using semantic embeddings and provides a search tool. Uses LangChain4j with AllMiniLmL6V2 embedding model and an in-memory vector store. Embeddings are rebuilt on server start.

**Purpose**: Enable AI agents to access large knowledge bases through semantic search without consuming limited context. The search tool is available on-demand via MCP, and only search results enter the agent's context.

**Key capabilities**:
- Semantic search across documentation, code, and knowledge bases
- Configurable ingest pipeline strategies (`:whole-document`, `:namespace-doc`, `:file-path`, `:code-analysis`, `:chunked`)
- Metadata filtering to narrow searches
- File watching for automatic re-indexing during development
- MCP resources for debugging ingestion and monitoring

See `doc/about.md` for detailed rationale.

## Common Commands

### Running the Server
```bash
clojure -X:run
# With custom config path:
clojure -X:run :config-path '"/path/to/config.edn"'
```

### Testing
```bash
# Run all tests
clojure -M:test

# Run specific test namespace
clojure -M:test --focus mcp-vector-search.config-test

# Run single test
clojure -M:test --focus mcp-vector-search.config-test/test-name
```

### REPL Development
The project requires `--enable-native-access=ALL-UNNAMED` JVM flag for the embedding model. When syncing deps after modifying deps.edn:
```clojure
(require 'clojure.repl.deps)
(clojure.repl.deps/sync-deps)
```

## Architecture

### Core Flow
1. **Startup** (`main.clj`): Reads config → starts system → ingests documents → creates MCP server with search tool
2. **Lifecycle** (`lifecycle.clj`): Manages embedding model and in-memory store in global atom
3. **Configuration** (`config.clj`): Parses path specs with globs, captures, and metadata
4. **Ingestion** (`ingest.clj`): Matches files, embeds content via configurable strategies, stores in vector DB
   - **Strategies** (`ingest/common.clj`, `ingest/chunked.clj`): Processing strategies in separate namespaces
5. **Search** (`tools.clj`): Exposes MCP search tool with metadata filtering
6. **Watching** (`watch.clj`): Optional file watching with debouncing for automatic re-indexing

### Configuration System

Config file location (first found is used):
- `.mcp-vector-search/config.edn` (project-specific)
- `~/.mcp-vector-search/config.edn` (global)

Config structure:

```clojure
{:description "Custom search tool description"  ; optional
 :watch? true                                   ; optional, enable file watching
 :sources [{:path "/docs/**/*.md"
            :name "Documentation"               ; optional, added to metadata
            :ingest :whole-document             ; optional, defaults to :whole-document
            :watch? true                        ; optional, overrides global :watch?
            :custom-key "custom-value"}]}       ; any additional keys become metadata
```

Path specs support:
- **Globs**: `*` (single level), `**` (recursive)
- **Named captures**: `(?<name>regex)` - captured values become metadata
- **Literal paths**: Direct file or directory paths
- Any additional keys in source maps become base metadata

The `process-config` function transforms `:sources` into `:path-specs` with parsed segments and base paths for filesystem walking.

See `doc/path-spec.md` for formal path specification syntax and `doc/using.md` for comprehensive configuration guide.

### Ingest Pipeline Strategies

Configurable via composable embedding and content strategies, orchestrated by the `process-document` multimethod. Single-segment strategies use a composable architecture, while multi-segment strategies (like `:chunked`) use direct implementations.

**Namespace Organization**:
- `ingest/common.clj` - Shared helpers and `process-document` multimethod definition
- `ingest/single_segment.clj` - Composable embedding and content strategies for single-segment processing
- `ingest/chunked.clj` - Chunked document processing strategy (multi-segment)
- Main `ingest.clj` requires all strategy namespaces

**Composable Architecture** (single-segment processing):

The `:single-segment` strategy separates concerns:
- **Embedding strategy** (`embed-content` multimethod) - What to search
- **Content strategy** (`extract-content` multimethod) - What to store and return

**Built-in Embedding Strategies**:
- `:whole-document` - Embed full file content
- `:namespace-doc` - Embed namespace docstring (Clojure files, adds `:namespace` metadata)
- `:code-analysis` - Embed code elements extracted via clj-kondo (see Multi-Segment Strategy below)

**Built-in Content Strategies**:
- `:whole-document` - Store full file content
- `:file-path` - Store only file path (reduces memory usage)

**Convenience Forwarding**:

For ergonomic configuration, these `:ingest` values forward to `:single-segment` with predefined combinations:
- `:whole-document` → embed full, store full
- `:namespace-doc` → embed ns doc, store full
- `:file-path` → embed full, store path only

**Multi-Segment Strategies**:
- `:chunked` - Splits large documents using LangChain4j recursive text splitter (separate namespace, not composable)
- `:code-analysis` - Analyzes code using clj-kondo, creates one segment per code element (vars, namespaces, classes, methods, fields, constructors)

**Configuration Examples**:

```clojure
;; Convenience syntax (recommended)
{:path "/src/**/*.clj"
 :ingest :namespace-doc}

;; Composable syntax (custom combinations)
{:path "/src/**/*.clj"
 :ingest :single-segment
 :embedding :namespace-doc
 :content-strategy :file-path}
```

To add new single-segment strategies, implement the `embed-content` or `extract-content` multimethods in `ingest/single_segment.clj`:
```clojure
(ns mcp-vector-search.ingest.single-segment
  ...)

(defmethod embed-content :custom
  [_strategy path content metadata]
  ;; Return {:text "..." :metadata {...}} or just "text"
  )

(defmethod extract-content :custom
  [_strategy path content metadata]
  ;; Return string to store
  )
```

For multi-segment strategies, implement `process-document` in a dedicated namespace like `ingest/chunked.clj`.

**Code Analysis Strategy Details**:

The `:code-analysis` strategy (`ingest/code_analysis.clj`) uses clj-kondo to analyze Clojure and Java source files, creating one segment per code element.

Configuration options (via source metadata):
```clojure
{:path "/src/**/*.clj"
 :ingest :code-analysis
 :visibility :public-only      ; :all (default) or :public-only
 :element-types #{:var :macro}} ; optional set to filter element types
```

**What gets embedded**: Docstring if present (non-empty after trimming), otherwise element name.

**What gets stored**: Complete clj-kondo analysis map for the element (as pr-str).

**Metadata added per segment**:
- `:element-type` - One of: var, macro, namespace, class, method, field, constructor
- `:element-name` - Fully qualified name (e.g., "my.ns/my-var", "com.example.MyClass.method")
- `:language` - "clojure" or "java"
- `:visibility` - "public", "private", or "protected"
- `:namespace` - Containing namespace/package (when present)

**Element type detection** (`code_analysis.clj:42-67`):
- Vars: Checks `:macro` flag to distinguish macros from regular vars
- Java constructors: Detected by `:method` flag without `:return-type`
- Java methods: Have `:method` flag with `:return-type`
- Java fields: Have `:field` flag and `:type`

**Edge cases handled**:
- Empty or whitespace-only docstrings are treated as absent, element name is used instead
- Analysis errors throw ex-info with `:type :analysis-error`
- Elements without type or name are filtered out
- Visibility filtering respects both Clojure (`:private` flag) and Java (`:private`, `:protected` flags)

### Metadata System

- Metadata comes from: base-metadata (source config) + captures (path spec regex groups)
- System tracks all metadata values in `:metadata-values` atom during ingestion
- Search tool dynamically generates JSON schema with enum constraints from discovered values
- Metadata filtering uses LangChain4j `IsEqualTo` filters combined with AND logic

**Metadata Keys**:
- Any keys in source config (except `:path`, `:name`, `:ingest`, `:watch?`) become base metadata
- Named captures from path specs (e.g., `(?<category>[^/]+)`) are added as metadata
- `:name` key is also added to metadata if provided
- `:doc-id` is automatically added with the file path (used for watch updates/deletes)
- `:namespace` is added by `:namespace-doc` embedding strategy

**Example**:
```clojure
{:path "/docs/(?<category>[^/]+)/*.md"
 :project "myapp"
 :type "documentation"}
```

For file `/docs/api/auth.md`:
- Metadata: `{:project "myapp", :type "documentation", :category "api", :doc-id "/docs/api/auth.md"}`

### File Watching

Optional file watching system for automatic re-indexing:

**Configuration**:
- Global `:watch? true` enables watching for all sources
- Per-source `:watch? true/false` overrides global setting
- Only files matching path spec patterns are processed

**Behavior**:
- Uses `hawk` library for filesystem watching
- Events are debounced (500ms) to avoid excessive re-indexing
- Operations:
  - File created → index new file
  - File modified → remove old embeddings by `:doc-id`, re-index
  - File deleted → remove embeddings by `:doc-id`
- Recursive watching for directories with `**` glob
- Base path (literal prefix) determines watch root directory

**Implementation**:
- `watch.clj/setup-watches` - Creates watchers for each path spec
- `watch.clj/handle-watch-event` - Processes file events with debouncing
- `watch.clj/stop-watches` - Cleanup on server shutdown

See `doc/using.md` for watch configuration examples and `test/mcp_vector_search/watch_integration_test.clj` for behavior specification.

### System Map

Global `system` atom in `lifecycle.clj` contains:
- `:embedding-model` - AllMiniLmL6V2EmbeddingModel instance
- `:embedding-store` - InMemoryEmbeddingStore instance
- `:metadata-values` - atom tracking `{field-key #{values...}}`
- `:watches` - atom containing active watch handlers (when `:watch?` enabled)

### MCP Resources

The server exposes five read-only MCP resources (`resources.clj`) for debugging configuration and monitoring ingestion:

**`ingestion://status`** - Overall ingestion summary
- Total documents processed
- Total segments created
- Total errors encountered
- Last ingestion timestamp

**`ingestion://stats`** - Per-source statistics
- Path spec that matched files
- Files matched vs. successfully processed
- Segments created per source
- Error counts per source

**`ingestion://failures`** - Last 20 ingestion errors (bounded queue)
- File path that failed
- Error type (`:read-error`, `:parse-error`, `:analysis-error`, etc.)
- Error message
- Source path spec that matched the file
- ISO-8601 timestamp

**`ingestion://metadata`** - Path segment capture metadata
- Path specs with named captures
- Example captured values for each capture name
- Useful for verifying regex patterns extracted expected metadata

**`ingestion://watch-stats`** - File watching statistics (when `:watch?` enabled)
- Global watch enabled status
- Per-source watch status
- Event counts (created, modified, deleted)
- Debounce statistics (queued, processed)
- Last event timestamp

All resources return JSON and include error handling. Access via MCP client's resource read functionality or debug by examining the `:ingestion-stats`, `:ingestion-failures`, `:path-captures`, and `:watch-stats` keys in the system atom.

## Key Implementation Details

### Path Spec Parsing
`config.clj` parses path strings into segment vectors with types `:literal`, `:glob`, or `:capture`. The `base-path` is the literal prefix used for filesystem walking, while the full pattern matches against complete file paths.

**Parsing algorithm** (see `doc/path-spec.md`):
1. If starts with `(?<` → parse as named capture
2. If starts with `**` → parse as recursive glob
3. If starts with `*` → parse as single-level glob
4. Otherwise → parse as literal

**Base path calculation**: Concatenation of literal segments from start until first non-literal segment. Determines starting directory for filesystem walking.

### File Matching
`ingest.clj/files-from-path-spec` handles two cases:
1. Literal file path (base-path is a file) - direct match
2. Directory (base-path is a dir) - walks tree, matches each file against full regex pattern

Regex captures are extracted and merged with base-metadata to produce final file metadata.

### Path Normalization
`ingest.clj` and `watch.clj` normalize absolute file paths to handle symlinks (e.g., `/var` → `/private/var` on macOS). This ensures document IDs match between ingestion and watch operations.

**Why needed**: Watch events provide canonical paths, but filesystem traversal may use raw paths. Without normalization, `.removeAll` operations during file updates/deletes won't find matching documents.

**Implementation** (`util.clj:10-31`):
- `normalize-file-path` function handles normalization centrally
- Absolute paths are normalized using `.getCanonicalPath()`
- Relative paths remain unchanged (preserves test compatibility)
- Used by both `ingest.clj` (lines 151, 171, 185) and `watch.clj` (lines 249, 292, 301)
- Pattern segments are rebuilt with normalized base path when needed
- Trailing separators (`/`) are preserved during segment reconstruction

### Search Tool
`tools.clj/search-tool` creates an MCP tool spec with dynamic schema. The schema's metadata parameter has enum constraints based on ingested values, ensuring clients only use valid filter values.

**Tool parameters**:
- `query` (required): Search text
- `limit` (optional): Max results (default: 10)
- `metadata` (optional): Metadata filters as key-value pairs (AND logic)

**Dynamic schema generation**: The tool schema is regenerated each time it's accessed, using current values from `:metadata-values` atom to populate enum constraints.

## Documentation Files

- **`README.md`** - Quick start, installation, basic usage
- **`doc/about.md`** - Project purpose, problem/solution, use cases
- **`doc/install.md`** - Detailed installation for various MCP clients
- **`doc/using.md`** - Complete configuration reference, strategies, file watching, troubleshooting
- **`doc/library-usage.md`** - Using mcp-vector-search as a library, bundling resources
- **`doc/path-spec.md`** - Formal path specification syntax and grammar

## Common Development Tasks

### Adding a New Embedding or Content Strategy

For single-segment processing, add methods to `src/mcp_vector_search/ingest/single_segment.clj`:

**New Embedding Strategy:**
```clojure
(defmethod embed-content :my-embedding
  [_strategy path content metadata]
  ;; Return map with :text and optionally :metadata
  {:text (extract-searchable-content content)
   :metadata {:custom-field "value"}})
```

**New Content Strategy:**
```clojure
(defmethod extract-content :my-content
  [_strategy path content metadata]
  ;; Return string to store
  (format-for-storage content))
```

Then use in configuration:
```clojure
{:sources [{:path "/data/**/*.txt"
            :ingest :single-segment
            :embedding :my-embedding
            :content-strategy :my-content}]}
```

### Adding a New Multi-Segment Strategy

1. Create a new namespace `src/mcp_vector_search/ingest/my_strategy.clj`:
```clojure
(ns mcp-vector-search.ingest.my-strategy
  (:require [mcp-vector-search.ingest.common :as common]))

(defmethod common/process-document :my-multi-segment
  [_strategy path content metadata]
  ;; Process content and create segment descriptors
  (let [segments (split-content content)]
    (map-indexed
      (fn [idx segment]
        (common/create-segment-descriptor
          path
          (common/generate-segment-id path idx)
          (:text segment)
          (:content segment)
          metadata))
      segments)))
```

2. Require the new namespace in `src/mcp_vector_search/ingest.clj`:
```clojure
(ns mcp-vector-search.ingest
  (:require
    ;; ... existing requires ...
    [mcp-vector-search.ingest.my-strategy]))
```

3. Document the strategy in `doc/using.md` under "Ingest Pipeline Strategies"
4. Add tests in `test/mcp_vector_search/ingest/my_strategy_test.clj`

### Testing Configuration Changes

Use `test/resources/` with test-specific subdirectories for fixtures:
```clojure
(def test-config-path "test/resources/my_test/config.edn")
```

This ensures test isolation and prevents fixture conflicts.

### Debugging Search Results

Check metadata values discovered during ingestion:
```clojure
@(:metadata-values @lifecycle/system)
;; => {:category #{"api" "guides"}, :project #{"myapp"}}
```

Verify embeddings were stored:
```clojure
(let [store (:embedding-store @lifecycle/system)]
  (.size (.embeddings store)))
;; => 42
```

Use MCP resources for detailed diagnostics (see "MCP Resources" section):
- `ingestion://status` - Overall ingestion summary
- `ingestion://stats` - Per-source statistics
- `ingestion://failures` - Recent errors
- `ingestion://metadata` - Captured metadata values
- `ingestion://watch-stats` - File watching metrics

## Code Style Notes

- Use doc strings for all public functions and macros (before argument vector)
- `deftest` forms do not have docstrings
- Start each test with a comment describing intention and contracts
- Wrap tests in outer `testing` form describing test subject
- Use BDD style for `testing` forms (should read like a specification when chained)
- Use `;;;` prefix for section separators in Clojure files (no `====` or `----`)
- Comment the WHY, not the WHAT
- Test strings should be short (≤20 chars if possible)
- Mark temporary code with `TODO` comments
- Mark tests matching current vs. intended behavior with `FIXME`

## See Also

- **[doc/using.md](doc/using.md)** - Complete user-facing configuration reference
- **[doc/path-spec.md](doc/path-spec.md)** - Formal path specification syntax
- **[doc/library-usage.md](doc/library-usage.md)** - Embedding mcp-vector-search as a library
- **[README.md](README.md)** - Quick start and project overview
