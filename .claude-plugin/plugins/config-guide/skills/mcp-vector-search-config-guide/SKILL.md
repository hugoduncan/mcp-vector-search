---
description: Guide for writing mcp-vector-search configuration files
---

# mcp-vector-search Configuration Guide

Comprehensive reference for writing `.mcp-vector-search/config.edn` configuration files.

## Overview

mcp-vector-search indexes documents using semantic embeddings and provides a search tool via the Model Context Protocol. Configuration controls which files are indexed, how they're processed, and what metadata is extracted.

## Configuration File Location

Create a configuration file at one of these locations (first found is used):

- **Project-specific**: `.mcp-vector-search/config.edn` (in project root)
- **Global**: `~/.mcp-vector-search/config.edn` (user home directory)

The server reads the configuration at startup and indexes all specified sources.

## Basic Configuration Structure

```clojure
{:description "Custom search tool description"  ; optional
 :watch? true                                   ; optional, enable file watching
 :sources [{:path "/docs/**/*.md"
            :name "Documentation"               ; optional
            :ingest :whole-document             ; optional, defaults to :whole-document
            :watch? true                        ; optional, overrides global :watch?
            :custom-key "custom-value"}]}       ; any additional keys become metadata
```

**Top-level keys:**
- `:description` - Custom description for the search tool (optional)
- `:watch?` - Enable automatic re-indexing when files change (optional, default: false)
- `:sources` - Array of source configurations (required)

## Path Specifications

### Filesystem vs Classpath Sources

**Filesystem sources** (`:path` key):
- Use absolute paths with leading `/`
- Support file watching for automatic re-indexing
- Files are read from the filesystem

```clojure
{:path "/docs/**/*.md"}
```

**Classpath sources** (`:class-path` key):
- Use relative paths without leading `/`
- File watching not available (read-only resources)
- Resources discovered from classpath (JARs, resource directories)
- Useful when embedding mcp-vector-search as a library

```clojure
{:class-path "docs/**/*.md"}
```

**Important**: Sources must specify exactly one of `:path` or `:class-path`.

### Glob Patterns

**Single-level glob** (`*`):
- Matches any characters within a single directory level
- Does not match path separators

```clojure
{:path "/docs/*.md"}           ; matches /docs/README.md
                                ; does NOT match /docs/api/guide.md
```

**Recursive glob** (`**`):
- Matches any characters across multiple directory levels
- Includes path separators

```clojure
{:path "/docs/**/*.md"}         ; matches /docs/README.md
                                ; matches /docs/api/guide.md
```

### Named Captures

Extract metadata from file paths using named regex groups:

```clojure
{:path "/docs/(?<category>[^/]+)/*.md"}
```

**Syntax**: `(?<name>pattern)`
- `name` - Metadata key (converted to keyword)
- `pattern` - Java regular expression

For file `/docs/api/functions.md`:
- Captures: `{:category "api"}`

**Multiple captures example:**
```clojure
{:path "/(?<project>[^/]+)/(?<version>v\\d+)/(?<file>.+\\.clj)"}
```

For file `/myapp/v1/core.clj`:
- Captures: `{:project "myapp", :version "v1", :file "core.clj"}`

### Path Specification Examples

```clojure
;; All markdown files recursively
{:path "/docs/**/*.md"}

;; Single directory level
{:path "/docs/*.md"}

;; Capture directory name
{:path "/docs/(?<category>[^/]+)/*.md"}

;; Multiple captures
{:path "/(?<project>[^/]+)/(?<version>[^/]+)/**/*.clj"}

;; Literal file
{:path "/docs/README.md"}

;; Classpath resource (no leading /)
{:class-path "docs/**/*.md"}
```

## Ingest Pipeline Strategies

Ingest strategies control how documents are processed, embedded, and stored. Set via the `:ingest` key.

### :whole-document (default)

Embeds and stores the entire file content as a single segment.

```clojure
{:sources [{:path "/docs/**/*.md"
            :ingest :whole-document}]}
```

**Characteristics:**
- One segment per file
- Both embedding and storage use full content
- Simple and straightforward for most use cases

**Use when**: You want to search across complete documents and return full content.

### :namespace-doc

For Clojure source files - embeds only the namespace docstring but stores the full file content.

```clojure
{:sources [{:path "/src/**/*.clj"
            :ingest :namespace-doc}]}
```

**Requirements:**
- File must contain a valid `ns` form
- Namespace must have a docstring
- Adds `:namespace` to metadata (e.g., `{:namespace "my.app.core"}`)

**Characteristics:**
- Embedding uses only namespace docstring
- Storage includes full file content
- One segment per file

**Use when**: You want to search Clojure namespaces by their documentation while still returning the complete source code.

### :file-path

Embeds the full content but stores only the file path.

```clojure
{:sources [{:path "/docs/**/*.md"
            :ingest :file-path}]}
```

**Characteristics:**
- Embedding uses full file content
- Storage contains only the file path
- One segment per file
- Reduces memory footprint for large document sets

**Use when:**
- You only need to discover which files match a query
- You want to reduce memory usage
- Your client will read file content separately

### :code-analysis

Analyzes Clojure and Java source files using clj-kondo to extract code elements (vars, namespaces, classes, methods, fields, macros). Creates one searchable segment per code element.

```clojure
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis}]}
```

**Configuration options:**

```clojure
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis
            :visibility :public-only        ; :all (default) | :public-only
            :element-types #{:var :macro}}]} ; optional filter
```

**`:visibility`** - Controls which elements to include:
- `:all` (default) - Include all elements regardless of visibility
- `:public-only` - Include only public elements
  - Clojure: Excludes vars with `^:private` or `{:private true}` metadata
  - Java: Excludes members with `private` or `protected` access modifiers

**`:element-types`** (optional) - Set of element types to include:
- Valid types: `:var`, `:macro`, `:namespace`, `:class`, `:method`, `:field`, `:constructor`
- If omitted: Include all element types
- If provided: Only include specified types

**Characteristics:**
- Multiple segments per file (one per code element)
- Embedding uses docstring if present, otherwise element name
- Content stores complete clj-kondo analysis map as EDN string
- Supports both Clojure (.clj, .cljs, .cljc) and Java (.java) files

**Segment metadata:**
- `:element-type` - Type of code element (var, macro, namespace, class, method, field, constructor)
- `:element-name` - Qualified name (e.g., "my.ns/my-fn" or "com.example.MyClass.myMethod")
- `:language` - Source language (clojure or java)
- `:namespace` - Containing namespace (Clojure) or package (Java)
- `:visibility` - Access level (public, private, or protected)

**Use when**: You want to search code by documentation or API discovery, finding functions/classes/methods based on their purpose rather than file names.

**Examples:**

```clojure
;; Search all code elements
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis}]}

;; Search only public API
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis
            :visibility :public-only}]}

;; Search only vars and macros
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis
            :element-types #{:var :macro}}]}

;; Java source code analysis
{:sources [{:path "/src/**/*.java"
            :ingest :code-analysis
            :visibility :public-only}]}
```

### :chunked

Splits documents into smaller segments using LangChain4j's recursive text splitter. Enables better semantic search for large documents.

```clojure
{:sources [{:path "/docs/**/*.md"
            :ingest :chunked
            :chunk-size 512
            :chunk-overlap 100}]}
```

**Configuration:**
- `:chunk-size` - Maximum characters per chunk (default: 512)
- `:chunk-overlap` - Characters to overlap between chunks (default: 100)

**Note**: LangChain4j's recursive paragraph splitter prioritizes semantic boundaries (paragraph breaks) over exact overlap amounts. Adjacent chunks may have less overlap than specified if splitting at a paragraph boundary.

**Characteristics:**
- Multiple segments per file
- Each chunk is embedded and stored independently
- All chunks from the same file share the same `:doc-id` for batch removal during updates
- Chunk metadata includes: `:chunk-index` (position), `:chunk-count` (total chunks), `:chunk-offset` (character offset)

**Chunk sizing guidance:**
- **Smaller chunks (256-512 chars)**: Better for precise fact-based retrieval
- **Larger chunks (1024+ chars)**: Better for broader context
- **Overlap (10-20%)**: Recommended to preserve context at chunk boundaries

**Use when**: You have large documents and need precise fact-based retrieval where specific information may be buried in lengthy content.

**Examples:**

```clojure
;; Fine-grained retrieval for technical docs
{:sources [{:path "/docs/**/*.md"
            :ingest :chunked
            :chunk-size 384
            :chunk-overlap 75}]}

;; Broader context for narrative content
{:sources [{:path "/articles/**/*.md"
            :ingest :chunked
            :chunk-size 1024
            :chunk-overlap 200}]}

;; Compare strategies for different content types
{:sources [
  ;; Small reference docs - whole document works well
  {:path "/api-reference/**/*.md"
   :ingest :whole-document}

  ;; Large guides - chunking improves precision
  {:path "/guides/**/*.md"
   :ingest :chunked
   :chunk-size 512
   :chunk-overlap 100}]}
```

## Metadata System

Metadata comes from two sources:

1. **Base metadata**: Any additional keys in the source map (except `:path`, `:class-path`, `:name`, `:ingest`, `:watch?`)
2. **Captures**: Values extracted from named groups in the path spec

```clojure
{:sources [{:path "/docs/(?<category>[^/]+)/*.md"
            :project "my-project"
            :type "documentation"}]}
```

For a file `/docs/api/functions.md`:
- Metadata: `{:project "my-project", :type "documentation", :category "api"}`

The `:name` key, if provided, is also added to metadata.

**System-added metadata:**
- `:doc-id` - File path (used for watch updates/deletes)
- `:file-id` - File path
- `:segment-id` - Unique segment identifier

**Strategy-specific metadata:**
- `:namespace-doc` adds: `:namespace`
- `:code-analysis` adds: `:element-type`, `:element-name`, `:language`, `:namespace`, `:visibility`
- `:chunked` adds: `:chunk-index`, `:chunk-count`, `:chunk-offset`

## File Watching

Optional file watching system for automatic re-indexing when files change.

**Configuration:**
- Global `:watch? true` enables watching for all sources
- Per-source `:watch? true/false` overrides global setting
- Only available for filesystem sources (`:path`), not classpath sources

```clojure
{:watch? true  ; enable globally
 :sources [
   {:path "/docs/**/*.md"}         ; watched (global setting)
   {:path "/src/**/*.clj"
    :watch? false}                  ; not watched (override)
   {:path "/notes/**/*.txt"
    :watch? true}]}                 ; watched (explicit)
```

**Behavior:**
- Events are debounced (500ms) to avoid excessive re-indexing
- File created → index new file
- File modified → remove old embeddings by `:doc-id`, re-index
- File deleted → remove embeddings by `:doc-id`
- Recursive watching for directories with `**` glob

## Complete Examples

### Basic Documentation Search

```clojure
{:sources [{:path "/Users/me/docs/**/*.md"}]}
```

### Multi-Source with Metadata

```clojure
{:description "Project documentation and code search"
 :sources [
   {:path "/Users/me/project/docs/**/*.md"
    :name "Documentation"
    :type "docs"}

   {:path "/Users/me/project/src/**/*.clj"
    :ingest :namespace-doc
    :name "Source Code"
    :type "code"}]}
```

### Metadata Extraction with Captures

```clojure
{:sources [{:path "/docs/(?<category>[^/]+)/*.md"
            :project "myapp"
            :type "documentation"}]}
```

### Code Analysis with Filtering

```clojure
{:sources [
   {:path "/src/**/*.clj"
    :ingest :code-analysis
    :visibility :public-only
    :element-types #{:var :macro}}

   {:path "/src/**/*.java"
    :ingest :code-analysis
    :visibility :public-only}]}
```

### Chunked Large Documents

```clojure
{:sources [
   {:path "/guides/**/*.md"
    :ingest :chunked
    :chunk-size 512
    :chunk-overlap 100}]}
```

### Mixed Filesystem and Classpath

```clojure
{:sources [
   ;; Filesystem documentation
   {:path "/Users/me/docs/**/*.md"
    :source "local"}

   ;; Bundled library documentation from classpath
   {:class-path "lib-docs/**/*.md"
    :source "library"}

   ;; Clojure source from classpath
   {:class-path "my/app/**/*.clj"
    :ingest :namespace-doc
    :source "library-code"}]}
```

### File Watching Enabled

```clojure
{:watch? true
 :sources [
   {:path "/Users/me/project/docs/**/*.md"}
   {:path "/Users/me/project/src/**/*.clj"
    :ingest :namespace-doc}]}
```

### Complex Multi-Strategy Configuration

```clojure
{:description "Comprehensive project search"
 :watch? true
 :sources [
   ;; API reference - small docs, keep whole
   {:path "/docs/api/**/*.md"
    :ingest :whole-document
    :category "api-reference"}

   ;; User guides - large docs, chunk them
   {:path "/docs/guides/**/*.md"
    :ingest :chunked
    :chunk-size 512
    :chunk-overlap 100
    :category "guides"}

   ;; Public API code
   {:path "/src/(?<namespace>[^/]+)/**/*.clj"
    :ingest :code-analysis
    :visibility :public-only
    :category "code"}

   ;; README files - whole document
   {:path "/(?<project>[^/]+)/README.md"
    :ingest :whole-document
    :category "readme"}]}
```

## Tips and Best Practices

**Path specifications:**
- Use absolute paths for filesystem sources (start with `/`)
- Use relative paths for classpath sources (no leading `/`)
- Named captures are powerful for extracting structured metadata
- Test path patterns with a small subset first

**Ingest strategies:**
- Start with `:whole-document` for most use cases
- Use `:namespace-doc` for Clojure codebases to search by documentation
- Use `:code-analysis` when you need fine-grained API discovery
- Use `:chunked` for large documents (>1000 chars)
- Use `:file-path` when you need to minimize memory usage

**Metadata:**
- Add meaningful metadata to enable filtering during search
- Use consistent naming conventions for metadata keys
- Captures are great for hierarchical organization (project, version, category)

**File watching:**
- Enable globally with `:watch? true` for development
- Disable for production or when working with static content
- Override per-source as needed
- Only works with filesystem sources, not classpath

**Performance:**
- Smaller chunk sizes create more segments (more memory, more precise search)
- Larger chunk sizes create fewer segments (less memory, broader context)
- `:file-path` strategy significantly reduces memory usage
- Consider the trade-off between search precision and resource usage
