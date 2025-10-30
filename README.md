# mcp-vector-search

**Semantic search for your documents, available to AI agents via MCP.**

Index documentation, code, and knowledge bases with semantic embeddings. AI assistants can search on-demand without loading everything into context.

```clojure
;; .mcp-vector-search/config.edn
{:sources [{:path "/docs/**/*.md"}]}
```

```
# Search from Claude Code
"Find authentication documentation"
→ Returns relevant docs based on semantic similarity
```

**Quick Links**: [Quick Start](#quick-start) · [Installation](#installation) · [What & Why](#what--why) · [Advanced Features](#advanced-features)

---

## Table of Contents

- [Quick Start](#quick-start)
- [What & Why](#what--why)
- [Installation](#installation)
- [Basic Configuration](#basic-configuration)
- [Using the Search Tool](#using-the-search-tool)
- [Advanced Features](#advanced-features)
- [Development](#development)
- [Documentation](#documentation)

## Quick Start

**1. Install the MCP server** (requires Clojure CLI tools):

```bash
# Add to ~/.clojure/deps.edn
{:aliases
 {:mcp-vector-search
  {:replace-paths []
   :replace-deps {org.hugoduncan/mcp-vector-search
                  {:git/url "https://github.com/hugoduncan/mcp-vector-search"
                   :git/sha "LATEST_SHA_HERE"}
                  org.clojure/clojure {:mvn/version "1.12.3"}}
   :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
   :exec-fn mcp-vector-search.main/start}}}

# Configure Claude Code
claude mcp add mcp-vector-search -- $(which clojure) -X:mcp-vector-search
```

**2. Create config file** at `~/.mcp-vector-search/config.edn` or `.mcp-vector-search/config.edn`:

```clojure
{:sources [{:path "/path/to/your/docs/**/*.md"}]}
```

**3. Restart Claude Code** - the search tool is now available.

**4. Search from Claude Code**:

```
"Search for authentication best practices"
"Find code examples for user validation"
"Show me documentation about API rate limiting"
```

The assistant will use the search tool to find relevant content semantically, without you loading all documents into context.

## What & Why

### What It Does

mcp-vector-search is an MCP server that:
- **Indexes documents** using semantic embeddings (AllMiniLmL6V2 model)
- **Provides a search tool** to AI assistants via the Model Context Protocol
- **Enables semantic search** - find content by meaning, not just keywords
- **Filters by metadata** - narrow searches to specific projects, categories, or file types

### Why Use It

**Expand what's accessible, not what's loaded.** AI agents have limited context windows. Instead of loading all documentation upfront, they search when needed.

**Key benefits**:
- Work with knowledge bases larger than context windows
- Discover related content through semantic similarity
- Retrieve information just-in-time, not speculatively
- The search tool doesn't consume context - only results do

**When to use**:
- Large documentation sets (hundreds of pages)
- Multi-project codebases (find patterns across projects)
- Historical context (design docs, decision logs)
- API references (look up functions and examples as needed)

See [doc/about.md](doc/about.md) for a detailed explanation of the problem and solution.

## Installation

### Prerequisites

- Clojure CLI tools
- Java 11+ (required for embedding model)

### Configure Clojure Alias

Add to `~/.clojure/deps.edn`:

```clojure
{:aliases
 {:mcp-vector-search
  {:replace-paths []
   :replace-deps {org.hugoduncan/mcp-vector-search
                  {:git/url "https://github.com/hugoduncan/mcp-vector-search"
                   :git/sha "LATEST_SHA_HERE"}
                  org.clojure/clojure {:mvn/version "1.12.3"}}
   :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
   :exec-fn mcp-vector-search.main/start}}}
```

**Note**: Replace `LATEST_SHA_HERE` with the latest commit SHA from this repository.

### Claude Code Setup

```bash
claude mcp add mcp-vector-search -- $(which clojure) -X:mcp-vector-search
```

### Other MCP Clients

**Claude Desktop** - Add to your config file:
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- Linux: `~/.config/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "mcp-vector-search": {
      "command": "clojure",
      "args": ["-X:mcp-vector-search"]
    }
  }
}
```

See [doc/install.md](doc/install.md) for detailed installation instructions and troubleshooting.

## Basic Configuration

Configuration file location (first found is used):
- `.mcp-vector-search/config.edn` (project-specific)
- `~/.mcp-vector-search/config.edn` (global)

### Minimal Example

```clojure
{:sources [{:path "/docs/**/*.md"}]}
```

Indexes all markdown files recursively under `/docs/`.

### Path Patterns

Path specs support globs and regex captures:

```clojure
;; All markdown in single directory
{:sources [{:path "/docs/*.md"}]}

;; Recursive (all subdirectories)
{:sources [{:path "/docs/**/*.md"}]}

;; Capture directory name as metadata
{:sources [{:path "/docs/(?<category>[^/]+)/*.md"}]}

;; Multiple sources
{:sources [{:path "/docs/**/*.md"}
           {:path "/src/**/*.clj"}]}
```

### Adding Metadata

Metadata enables filtering searches to specific subsets:

```clojure
{:sources [{:path "/docs/(?<category>[^/]+)/*.md"
            :project "myapp"
            :type "documentation"}]}
```

For file `/docs/api/auth.md`:
- Metadata: `{:project "myapp", :type "documentation", :category "api"}`

Any keys except `:path`, `:name`, `:pipeline`, and `:watch?` become metadata. Named captures (`(?<name>...)`) are also added as metadata.

See [doc/path-spec.md](doc/path-spec.md) for the formal path specification syntax.

## Using the Search Tool

Once configured, AI assistants can search indexed documents.

### Basic Search

Simply ask the assistant to find information:

```
"Find documentation about rate limiting"
"Show me examples of error handling"
"Search for authentication setup instructions"
```

The assistant will use the search tool automatically.

### Metadata Filtering

Searches can filter by metadata:

```
"Search for API documentation in the admin category"
"Find Clojure source files related to database connections"
```

The assistant can use metadata filters like:

```json
{
  "query": "database connection",
  "metadata": {
    "category": "api",
    "type": "clojure"
  }
}
```

### Search Parameters

- `query` (required): Search text
- `limit` (optional): Max results (default: 10)
- `metadata` (optional): Filter by metadata key-value pairs (AND logic)

### Example Workflows

**Documentation discovery**:
```
"What documentation exists about user authentication?"
→ Returns: auth.md, security-guide.md, user-management.md
```

**Code pattern finding**:
```
"Find code examples of input validation in the API layer"
→ Returns: Relevant source files with validation logic
```

**Just-in-time reference**:
```
"How do I configure the caching system?"
→ Searches config docs, returns relevant sections
```

## Advanced Features

### Pipeline Strategies

Processing strategies control how documents are processed, embedded, and stored. Set via the `:pipeline` key.

**`:whole-document` (default)** - Embeds and stores entire file:

```clojure
{:sources [{:path "/docs/**/*.md"
            :pipeline :whole-document}]}
```

**`:namespace-doc`** - For Clojure files, embeds only namespace docstring but returns full file:

```clojure
{:sources [{:path "/src/**/*.clj"
            :pipeline :namespace-doc}]}
```

Useful for searching Clojure namespaces by description while returning complete source code. Adds `:namespace` to metadata.

**`:file-path`** - Embeds content but stores only the file path:

```clojure
{:sources [{:path "/archive/**/*.txt"
            :pipeline :file-path}]}
```

Reduces memory for large document sets. Embeddings are still created from full content, but search results only include file paths. Your client can read files separately.

### File Watching

Auto-reindex when files change during development:

```clojure
;; Watch all sources
{:watch? true
 :sources [{:path "/docs/**/*.md"}
           {:path "/src/**/*.clj"}]}

;; Watch specific sources
{:sources [{:path "/docs/**/*.md"
            :watch? true}
           {:path "/archive/**/*.md"
            :watch? false}]}
```

Changes are debounced (500ms) and automatically update the index.

### Custom Search Description

Customize the search tool description for AI assistants:

```clojure
{:description "Search internal API documentation and code examples"
 :sources [{:path "/docs/**/*.md"}]}
```

### Complete Example

```clojure
{:description "Search project documentation and source code"
 :watch? true
 :sources [
   ;; Documentation with category metadata
   {:path "/docs/(?<category>[^/]+)/**/*.md"
    :project "myapp"
    :type "docs"}

   ;; Clojure source - search by namespace doc
   {:path "/src/**/*.clj"
    :pipeline :namespace-doc
    :project "myapp"
    :type "source"}

   ;; Test files
   {:path "/test/**/*.clj"
    :pipeline :namespace-doc
    :project "myapp"
    :type "test"}

   ;; Large archive - path-only to save memory
   {:path "/archive/**/*.txt"
    :pipeline :file-path
    :watch? false}]}
```

See [doc/using.md](doc/using.md) for comprehensive configuration details and troubleshooting.

## Development

### Setup

```bash
git clone https://github.com/hugoduncan/mcp-vector-search
cd mcp-vector-search
```

### Running Locally

```bash
# Start server with default config (~/.mcp-vector-search/config.edn)
clojure -X:run

# Use custom config
clojure -X:run :config-path '"/path/to/config.edn"'
```

### Testing

```bash
# Run all tests
clojure -M:test

# Run specific namespace
clojure -M:test --focus mcp-vector-search.config-test
```

### REPL Development

```clojure
(require 'clojure.repl.deps)
(clojure.repl.deps/sync-deps)
```

### Project Structure

- `main.clj` - Server startup and coordination
- `config.clj` - Configuration parsing and path spec handling
- `ingest.clj` - File matching, embedding, and storage (with strategy multimethods)
- `tools.clj` - MCP search tool definition
- `lifecycle.clj` - System lifecycle management
- `server.clj` - MCP protocol implementation

### Contributing

Contributions welcome! Please:
1. Run tests before submitting PRs
2. Follow existing code style
3. Add tests for new features
4. Update documentation

## Documentation

- **[doc/about.md](doc/about.md)** - Project purpose, problem/solution, use cases
- **[doc/install.md](doc/install.md)** - Detailed installation for various MCP clients
- **[doc/using.md](doc/using.md)** - Complete configuration reference, strategies, troubleshooting
- **[doc/path-spec.md](doc/path-spec.md)** - Formal path specification syntax and grammar

## License

Copyright © 2025 Hugo Duncan

Distributed under the Eclipse Public License 2.0.
