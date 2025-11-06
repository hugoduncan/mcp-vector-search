---
description: Guide for embedding mcp-vector-search as a library in MCP servers
---

# mcp-vector-search Library Integration Guide

Comprehensive guide for embedding mcp-vector-search within your own MCP server to provide semantic search capabilities over bundled documentation or resources.

## Overview

mcp-vector-search can be used as a library to add semantic search to custom MCP servers. This enables you to bundle documentation with your server and provide semantic search without requiring users to configure vector search separately.

**Use cases:**
- Bundle documentation with your MCP server
- Provide semantic search over application-specific knowledge bases
- Combine vector search with your own custom tools
- Deliver turnkey MCP servers with integrated search

## Adding the Dependency

Add mcp-vector-search to your `deps.edn`:

```clojure
{:deps
 {org.hugoduncan/mcp-vector-search
  {:git/url "https://github.com/hugoduncan/mcp-vector-search"
   :git/sha "LATEST_SHA_HERE"}}}
```

**Important**: Replace `LATEST_SHA_HERE` with the latest commit SHA from the repository. You can find this by visiting the repository or running:

```bash
git ls-remote https://github.com/hugoduncan/mcp-vector-search HEAD
```

## Classpath vs Filesystem Sources

mcp-vector-search supports two types of source specifications:

### Filesystem Sources

Use the `:path` key with absolute paths:

```clojure
{:sources [{:path "/Users/me/docs/**/*.md"}]}
```

**Characteristics:**
- Absolute paths starting with `/`
- Files read from the filesystem
- Support file watching for automatic re-indexing
- Used for development and user-specific content

### Classpath Sources

Use the `:class-path` key with relative paths:

```clojure
{:sources [{:class-path "docs/**/*.md"}]}
```

**Characteristics:**
- Relative paths without leading `/`
- Resources discovered from classpath (JARs, resource directories)
- No file watching (read-only resources)
- Used for bundled, library-embedded content

**When to use each:**
- **Filesystem**: Development, user-specific content, files that change
- **Classpath**: Production, bundled resources, static documentation

You can mix both types in a single configuration to combine bundled resources with user-specific content.

## Resource Bundling

### Directory Structure

Organize your project to bundle resources:

```
my-mcp-server/
├── resources/
│   ├── .mcp-vector-search/
│   │   └── config.edn          # Configuration for bundled resources
│   └── docs/
│       ├── guide.md
│       ├── api/
│       │   └── reference.md
│       └── examples/
│           └── usage.md
├── src/
│   └── my_server/
│       └── main.clj
└── deps.edn
```

**Key points:**
- Place documentation in `resources/` directory
- Configuration goes in `resources/.mcp-vector-search/config.edn`
- Resources will be bundled in the JAR when you package your server
- Standard Clojure project structure with `resources/` on the classpath

### Configuration for Classpath Resources

Create `resources/.mcp-vector-search/config.edn`:

```clojure
{:description "Search my-server documentation"
 :sources [{:class-path "docs/**/*.md"
            :name "Documentation"
            :type "docs"}]}
```

**Configuration rules:**
- Use `:class-path` instead of `:path`
- Paths are relative (no leading `/`)
- All path spec features work: globs (`**/*.md`), named captures (`(?<category>[^/]+)`)
- Metadata extraction works identically
- File watching (`:watch?`) has no effect on classpath sources

### Starting the Server

In your MCP server's main namespace:

```clojure
(ns my-server.main
  (:require
    [mcp-vector-search.main :as vector-search]))

(defn -main []
  ;; Start vector search with classpath config
  ;; The config at resources/.mcp-vector-search/config.edn
  ;; will be found automatically on the classpath
  (vector-search/start)

  ;; Your server code here
  ...)
```

The server will:
1. Search for `.mcp-vector-search/config.edn` on the classpath
2. Discover and index resources from `:class-path` sources
3. Provide the search tool via MCP

## Configuration Loading Order

When using mcp-vector-search as a library, configuration is loaded from the first location found:

1. **Classpath**: `resources/.mcp-vector-search/config.edn` (bundled in JAR)
2. **Project directory**: `./.mcp-vector-search/config.edn` (runtime override)
3. **Home directory**: `~/.mcp-vector-search/config.edn` (user-specific)

This loading order enables:
- **Bundling defaults**: Ship default configuration in your JAR
- **Project overrides**: Provide project-specific config during development
- **User customization**: Allow users to customize via home directory

**Example workflow:**

Development with project override:
```bash
# Create project-specific config during development
mkdir -p .mcp-vector-search
cat > .mcp-vector-search/config.edn <<EOF
{:description "Development search with local files"
 :watch? true
 :sources [{:path "/Users/me/project/docs/**/*.md"}]}
EOF
```

Production with bundled config:
```bash
# Build JAR - includes resources/.mcp-vector-search/config.edn
clojure -T:build uber
# Run - uses bundled config
java -jar target/my-server.jar
```

## JAR Packaging

### Build Configuration

Ensure `resources/` is in your `:paths` in `deps.edn`:

```clojure
{:paths ["src" "resources"]
 :deps {org.clojure/clojure {:mvn/version "1.12.3"}
        org.hugoduncan/mcp-vector-search
        {:git/url "https://github.com/hugoduncan/mcp-vector-search"
         :git/sha "LATEST_SHA_HERE"}}
 :aliases
 {:build {:deps {io.github.clojure/tools.build {:mvn/version "0.9.6"}}
          :ns-default build}}}
```

### Build Script

Create `build.clj`:

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/my-server.jar")

(defn uber [_]
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis (b/create-basis)
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis (b/create-basis)
           :main 'my-server.main}))
```

**Important**: Include both `"src"` and `"resources"` in `:src-dirs` to bundle all resources.

### Building and Running

```bash
# Build the JAR
clojure -T:build uber

# Verify resources are included
jar tf target/my-server.jar | grep -E "(docs|.mcp-vector-search)"

# Run the server
java -jar target/my-server.jar
```

Users can now run your MCP server with semantic search over your bundled documentation, without any configuration needed.

## Example Configurations

### Bundled API Documentation

```clojure
;; resources/.mcp-vector-search/config.edn
{:description "Search API documentation and examples"
 :sources [{:class-path "api-docs/**/*.md"
            :category "api"}
           {:class-path "examples/**/*.clj"
            :ingest :namespace-doc
            :category "examples"}]}
```

### Mixed Classpath and Filesystem

Combine bundled resources with filesystem sources:

```clojure
{:description "Search bundled docs and local project files"
 :sources [
   ;; Bundled documentation (from JAR)
   {:class-path "lib-docs/**/*.md"
    :source "library"
    :type "docs"}

   ;; Local project documentation (filesystem)
   {:path "/Users/me/project/docs/**/*.md"
    :source "project"
    :type "docs"}

   ;; Local source code (filesystem)
   {:path "/Users/me/project/src/**/*.clj"
    :ingest :namespace-doc
    :source "project"
    :type "source"}]}
```

### Multi-Language Documentation

Extract language metadata from paths:

```clojure
{:description "Search multi-language documentation"
 :sources [{:class-path "docs/(?<lang>en|es|fr)/**/*.md"
            :type "documentation"}]}
```

For resource `docs/en/guide.md`:
- Metadata: `{:type "documentation", :lang "en"}`

### Code Analysis for API Discovery

```clojure
{:description "Search bundled API documentation"
 :sources [{:class-path "src/**/*.clj"
            :ingest :code-analysis
            :visibility :public-only
            :category "api"}]}
```

## Complete Example

### Directory Structure

```
my-mcp-server/
├── resources/
│   ├── .mcp-vector-search/
│   │   └── config.edn
│   └── docs/
│       ├── README.md
│       └── api/
│           └── reference.md
├── src/
│   └── my_server/
│       └── main.clj
├── deps.edn
└── build.clj
```

### Configuration File

```clojure
;; resources/.mcp-vector-search/config.edn
{:description "Search my-server documentation"
 :sources [{:class-path "docs/**/*.md"
            :project "my-server"
            :type "documentation"}]}
```

### Main Server Code

```clojure
;; src/my_server/main.clj
(ns my-server.main
  (:require
    [mcp-vector-search.main :as vector-search]
    [mcp-vector-search.server :as mcp-server]))

(defn -main []
  ;; Start vector search (loads classpath config automatically)
  (vector-search/start)

  ;; Add your custom tools here
  ;; ...

  ;; Start MCP server
  (mcp-server/start!))
```

### Dependencies

```clojure
;; deps.edn
{:paths ["src" "resources"]
 :deps {org.clojure/clojure {:mvn/version "1.12.3"}
        org.hugoduncan/mcp-vector-search
        {:git/url "https://github.com/hugoduncan/mcp-vector-search"
         :git/sha "LATEST_SHA_HERE"}}
 :aliases
 {:build {:deps {io.github.clojure/tools.build {:mvn/version "0.9.6"}}
          :ns-default build}}}
```

### Build Configuration

```clojure
;; build.clj
(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/my-server.jar")

(defn uber [_]
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis (b/create-basis)
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis (b/create-basis)
           :main 'my-server.main}))
```

## Limitations

### No File Watching for Classpath Sources

Classpath sources do not support file watching:
- Resources in JARs are read-only
- Classpath resources are indexed once at startup
- The `:watch?` flag has no effect on `:class-path` sources

**Workaround for development**: Use filesystem sources instead:

```clojure
;; Development config (local file) - with watching
{:watch? true
 :sources [{:path "/Users/me/project/resources/docs/**/*.md"}]}

;; Production config (bundled) - no watching needed
{:sources [{:class-path "docs/**/*.md"}]}
```

### Resource Discovery Performance

Classpath resource discovery walks the entire classpath:
- Use specific base paths to limit scope (e.g., `docs/**/*.md` not `**/*.md`)
- Initial indexing may take longer than filesystem sources
- Avoid overly broad patterns for large classpaths

## Troubleshooting

### Configuration Not Found

**Error**: `No configuration file found`

**Check:**
1. File is in `resources/.mcp-vector-search/config.edn`
2. `resources/` is on the classpath (should be automatic in `:paths`)
3. File is valid EDN syntax
4. No typos in directory or file name

**Verify config is on classpath:**
```clojure
(require '[clojure.java.io :as io])
(io/resource ".mcp-vector-search/config.edn")  ;; Should return URL
```

### Resources Not Indexed

**Error**: `No documents indexed from classpath sources`

**Check:**
1. Resources exist in `resources/` directory
2. Path patterns match your directory structure
3. No leading `/` in `:class-path` values (should be relative)
4. Resources are included in the JAR (for packaged servers)

**Verify resources are on classpath:**
```clojure
(require '[clojure.java.io :as io])
(io/resource "docs/guide.md")  ;; Should return URL
```

### JAR Build Issues

**Error**: Resources missing from JAR

**Check:**

1. Ensure `resources/` is in `:paths` in `deps.edn`:
```clojure
{:paths ["src" "resources"]
 :deps {...}}
```

2. Ensure `resources/` is in `:src-dirs` in `build.clj`:
```clojure
(b/copy-dir {:src-dirs ["src" "resources"]
             :target-dir class-dir})
```

3. Verify the JAR contains resources:
```bash
jar tf my-server.jar | grep docs
jar tf my-server.jar | grep .mcp-vector-search
```

### Path Pattern Issues

**Error**: Some resources not matched

**Common mistakes:**
- Using leading `/` with `:class-path` (should be relative)
- Forgetting `**` for recursive matching
- Regex special characters not escaped in captures

**Test patterns:**
```clojure
;; Wrong - leading slash
{:class-path "/docs/**/*.md"}

;; Correct - relative path
{:class-path "docs/**/*.md"}

;; Wrong - single glob doesn't recurse
{:class-path "docs/*.md"}  ;; only matches docs/file.md

;; Correct - recursive glob
{:class-path "docs/**/*.md"}  ;; matches docs/api/file.md
```

## Tips and Best Practices

**Resource organization:**
- Place all bundled content in `resources/` directory
- Use clear directory structure (e.g., `docs/`, `api/`, `examples/`)
- Include `.mcp-vector-search/config.edn` in `resources/`
- Keep resources focused on bundled content

**Configuration strategy:**
- Ship sensible defaults in `resources/.mcp-vector-search/config.edn`
- Use relative paths with `:class-path` for bundled content
- Document how users can override via project or home directory config
- Provide example configs in your server's README

**Path specifications:**
- Use specific base paths (e.g., `docs/**/*.md` not `**/*.md`)
- Leverage named captures for metadata extraction
- Test path patterns during development
- Avoid overly broad patterns for performance

**Build process:**
- Always include `"resources"` in both `:paths` and `:src-dirs`
- Verify JAR contents after building
- Test the JAR in a clean environment
- Document build requirements in your README

**Development workflow:**
- Use filesystem sources with `:watch?` during development
- Switch to classpath sources for production builds
- Keep development and production configs separate
- Test with both config types before releasing

**Testing:**
- Verify config loading from classpath
- Check resource discovery and indexing
- Test search functionality with bundled resources
- Validate JAR packaging and distribution

## See Also

- **doc/using.md** - Complete configuration reference for all strategies and options
- **doc/path-spec.md** - Formal syntax for path specifications and captures
- **doc/about.md** - Project purpose and use cases
- **README.md** - Quick start guide for mcp-vector-search
