# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

An MCP (Model Context Protocol) server that indexes documents using semantic embeddings and provides a search tool. Uses LangChain4j with AllMiniLmL6V2 embedding model and an in-memory vector store. Embeddings are rebuilt on server start.

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
5. **Search** (`tools.clj`): Exposes MCP search tool with metadata filtering

### Configuration System

Config file (`.mcp-vector-search/config.edn`) specifies sources to index:

```clojure
{:sources [{:path "/docs/**/*.md"
            :name "README"           ; optional metadata
            :embedding :whole-document  ; optional, defaults to :whole-document
            :ingest :whole-document}]}  ; optional, defaults to :whole-document
```

Path specs support:
- Globs: `*` (single level), `**` (recursive)
- Named captures: `(?<name>regex)` - captured values become metadata
- Any additional keys in source maps become base metadata

The `process-config` function transforms `:sources` into `:path-specs` with parsed segments and base paths for filesystem walking.

### Embedding & Ingest Strategies

Configurable via multimethods to support different document processing approaches:

- `embed-content` multimethod: dispatches on `:embedding` strategy, creates embeddings from content
- `ingest-segments` multimethod: dispatches on `:ingest` strategy, stores embeddings

Default `:whole-document` strategy embeds entire file as single segment (see `ingest.clj:113-148`).

To add new strategies, implement both multimethods:
```clojure
(defmethod embed-content :custom-strategy
  [_strategy embedding-model content metadata]
  ;; Return {:embedding-response ... :segment ...}
  )

(defmethod ingest-segments :custom-strategy
  [_strategy embedding-store embedding-result]
  ;; Store embeddings, return nil
  )
```

### Metadata System

- Metadata comes from: base-metadata (source config) + captures (path spec regex groups)
- System tracks all metadata values in `:metadata-values` atom during ingestion
- Search tool dynamically generates JSON schema with enum constraints from discovered values
- Metadata filtering uses LangChain4j `IsEqualTo` filters combined with AND logic

### System Map

Global `system` atom in `lifecycle.clj` contains:
- `:embedding-model` - AllMiniLmL6V2EmbeddingModel instance
- `:embedding-store` - InMemoryEmbeddingStore instance
- `:metadata-values` - atom tracking `{field-key #{values...}}`

## Key Implementation Details

### Path Spec Parsing
`config.clj` parses path strings into segment vectors with types `:literal`, `:glob`, or `:capture`. The `base-path` is the literal prefix used for filesystem walking, while the full pattern matches against complete file paths.

### File Matching
`ingest.clj/files-from-path-spec` handles two cases:
1. Literal file path (base-path is a file) - direct match
2. Directory (base-path is a dir) - walks tree, matches each file against full regex pattern

Regex captures are extracted and merged with base-metadata to produce final file metadata.

### Search Tool
`tools.clj/search-tool` creates an MCP tool spec with dynamic schema. The schema's metadata parameter has enum constraints based on ingested values, ensuring clients only use valid filter values.
