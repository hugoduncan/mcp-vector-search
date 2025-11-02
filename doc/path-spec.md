# Path Specification Syntax

This document provides a formal specification for the path syntax used in mcp-vector-search configuration files.

## Purpose

Path specifications define patterns for matching filesystem paths. They support literal paths, glob patterns for flexible matching, and named captures for extracting metadata from matched paths.

## Syntax Grammar

```ebnf
path-spec     ::= segment*
segment       ::= literal | glob | capture
literal       ::= <any character except '(', '*'>+
glob          ::= '**' | '*'
capture       ::= '(?<' name '>' pattern ')'
name          ::= <non-empty string not containing '>'>
pattern       ::= <valid Java regular expression>
```

## Segment Types

### Literal Segments

**Syntax**: Any sequence of characters not starting with `(` or `*`

**Semantics**: Matched exactly against the filesystem path

**Regex Translation**: Wrapped in `Pattern.quote()` for literal matching

**Examples**:
- `/docs/` → matches literal string "/docs/"
- `README.md` → matches literal string "README.md"

### Glob Patterns

#### Single-Level Glob (`*`)

**Syntax**: `*`

**Semantics**: Matches any sequence of characters within a single directory level (excludes `/`)

**Regex Translation**: `[^/]*`

**Examples**:
- `*.md` → matches "README.md", "guide.md"
- `*.md` → does NOT match "foo/bar.md" (contains path separator)

#### Recursive Glob (`**`)

**Syntax**: `**`

**Semantics**: Matches any sequence of characters across multiple directory levels (includes `/`)

**Regex Translation**: `.*?` (non-greedy)

**Examples**:
- `/docs/**/*.md` → matches "/docs/README.md", "/docs/api/functions.md"
- `**` → matches any path

### Named Captures

**Syntax**: `(?<name>pattern)`

**Semantics**: Matches the regex pattern and extracts the matched text as metadata with the given name

**Regex Translation**: Passed through as Java named group `(?<name>pattern)`

**Metadata**: Captured value is added to file metadata as `{:name "captured-value"}`

**Constraints**:
- Name must be non-empty
- Name must not contain `>`
- Pattern must be valid Java regular expression

**Examples**:
- `(?<category>[^/]+)` → captures directory name as `:category`
- `(?<version>v\d+\.\d+)` → captures version string matching pattern

## Parsing Algorithm

The parser processes the input string left-to-right:

1. If remaining input starts with `(?<`, parse as capture:
   - Extract name between `(?<` and `>`
   - Extract pattern between `>` and `)`
   - Validate pattern compiles as Java regex
   - Create `{:type :capture, :name name, :pattern pattern}`

2. If remaining input starts with `**`, parse as recursive glob:
   - Create `{:type :glob, :pattern "**"}`

3. If remaining input starts with `*`, parse as single-level glob:
   - Create `{:type :glob, :pattern "*"}`

4. Otherwise, parse as literal:
   - Collect characters until next special (`(?<`, `**`, or `*`)
   - Create `{:type :literal, :value text}`

5. Repeat until input is exhausted

## Base Path Calculation

The **base path** is the concatenation of literal segments from the start until the first non-literal segment.

**Purpose**: Determines the starting directory for filesystem walking

**Examples**:
- `/docs/**/*.md` → base path: `/docs/`
- `/docs/README.md` → base path: `/docs/README.md`
- `**/*.md` → base path: `` (empty, walks from current directory)

## Pattern Matching

The parsed segments are compiled into a complete Java regex pattern that matches against full file paths:

1. Each segment is converted to its regex equivalent
2. Segments are concatenated to form the complete pattern
3. Pattern is compiled to `java.util.regex.Pattern`
4. Matching uses `Pattern.matches()` (anchored match)

For files in directories:
1. Filesystem walk starts from base path
2. Each file's complete path is tested against the pattern
3. Named captures are extracted via `Matcher.group(name)`

## Error Conditions

### Malformed Capture Errors

**Missing `>` in name**:
```
Path: "(?<name*.md"
Error: "Malformed capture: missing '>' in name"
```

**Missing closing `)`**:
```
Path: "(?<name>pattern*.md"
Error: "Malformed capture: missing closing ')'"
```

**Empty capture name**:
```
Path: "(?<>pattern)"
Error: "Malformed capture: missing capture name"
```

**Invalid regex pattern**:
```
Path: "(?<name>[)"
Error: "Invalid regex in capture"
Cause: PatternSyntaxException details
```

## Escaping and Special Characters

### Matching Literal Special Characters

To match literal `*`, `(`, or `)` characters, place them within a capture group:

**Example**: Match literal asterisk in filename
```
/docs/(?<filename>foo\*bar\.md)
```

**Note**: The pattern inside captures uses standard Java regex escaping rules.

### Path Separators

Path specs use `/` as the path separator regardless of platform. The matcher operates on paths normalized to use `/`.

## Limitations

1. **No alternation at top level**: Cannot use `(foo|bar)` outside captures
2. **No character classes at top level**: Cannot use `[abc]` outside captures
3. **Glob specificity**: `*` and `**` have fixed meanings, cannot be customized
4. **Capture-only regex**: Complex regex patterns only available inside captures
5. **No lookahead/lookbehind**: While technically valid in captures, may cause unexpected behavior with path matching

## Complete Examples

### Example 1: Simple Recursive Match
```clojure
{:path "/docs/**/*.md"}
```
- Segments: `[{:type :literal :value "/docs/"} {:type :glob :pattern "**"} {:type :literal :value "/"} {:type :glob :pattern "*"} {:type :literal :value ".md"}]`
- Base path: `/docs/`
- Regex: `/docs/.*?/[^/]*\.md`
- Matches: `/docs/README.md`, `/docs/api/functions.md`

### Example 2: Named Capture
```clojure
{:path "/docs/(?<category>[^/]+)/*.md"}
```
- Segments: `[{:type :literal :value "/docs/"} {:type :capture :name "category" :pattern "[^/]+"} {:type :literal :value "/"} {:type :glob :pattern "*"} {:type :literal :value ".md"}]`
- Base path: `/docs/`
- Regex: `/docs/(?<category>[^/]+)/[^/]*\.md`
- Matches: `/docs/api/functions.md` with `:category "api"`

### Example 3: Multiple Captures
```clojure
{:path "/(?<project>[^/]+)/(?<version>v\d+)/(?<file>.+\.clj)"}
```
- Base path: `/` (or empty)
- Captures: `:project`, `:version`, `:file`
- Matches: `/myapp/v1/core.clj` with `{:project "myapp" :version "v1" :file "core.clj"}`

### Example 4: Literal File
```clojure
{:path "/docs/README.md"}
```
- Segments: `[{:type :literal :value "/docs/README.md"}]`
- Base path: `/docs/README.md`
- Regex: `/docs/README\.md`
- Matches: `/docs/README.md` only

## Implementation Notes

### Performance Characteristics

- Pattern compilation occurs once during configuration processing
- Regex compilation uses `Pattern.compile()` with no special flags
- No caching of match results
- Filesystem walking uses depth-first traversal

### Platform Considerations

- Paths are normalized to use `/` separator before matching
- Base path calculation assumes `/` separator
- Actual filesystem operations use platform-specific paths

### Capture Metadata

- Capture names are converted to keywords (`:name`)
- Captured values are strings
- Captures are merged with base metadata (captures take precedence)
- If a capture group doesn't match, it's not included in metadata

## See Also

- **[using.md](using.md)** - Configuration guide with path spec examples and strategies
- **[library-usage.md](library-usage.md)** - Using path specs with classpath sources
- **[../README.md](../README.md)** - Quick start examples using basic path patterns
- **[../CLAUDE.md](../CLAUDE.md)** - Implementation details of path spec parsing
