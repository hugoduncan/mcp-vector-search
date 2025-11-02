# README.md Simplification Notes

**For Task 4 (doc/using.md updates)**

## Summary

Reduced README.md from 423 lines to 288 lines (135 lines removed, 32% reduction).

## Content Moved/Simplified

### 1. Advanced Features - Ingest Strategies (lines 269-296)
**Before:** Detailed multi-paragraph descriptions of each strategy with multiple code examples
**After:** Bullet list with one-line descriptions, single code example
**Moved to:** Brief overview kept, detailed examples should be in doc/using.md

Content simplified:
- `:whole-document` - Full description → one-liner
- `:namespace-doc` - Full description → one-liner
- `:file-path` - Full description → one-liner
- **Added:** `:code-analysis` and `:chunked` to the list (were missing)

### 2. File Watching (lines 299-316)
**Before:** Two separate examples (global watch, per-source watch)
**After:** Single minimal example with global watch
**Moved to:** Advanced per-source override examples should be in doc/using.md

### 3. Complete Configuration Example (lines 327-353)
**Before:** Large multi-source example with comments
**After:** Removed entirely
**Moved to:** This comprehensive example belongs in doc/using.md

### 4. Path Patterns (lines 168-185)
**Before:** Five separate examples with comments
**After:** Two examples (basic recursive, with capture/metadata)
**Moved to:** Additional examples should be in doc/using.md

### 5. Metadata Section (lines 187-202)
**Before:** Standalone section with detailed explanation
**After:** Merged with Path Patterns, condensed explanation
**Moved to:** Full explanation in doc/using.md

### 6. Using the Search Tool (lines 186-247)
**Before:** Four subsections with multiple examples and JSON
**After:** Single section with key examples, brief parameter list
**Moved to:** Detailed workflow examples should be in doc/using.md

### 7. Installation - Other MCP Clients (lines 136-150)
**Before:** Full Claude Desktop config with platform-specific paths
**After:** Brief mention with link to doc/install.md
**Moved to:** Full instructions in doc/install.md (already there)

## Cross-References Added/Updated

- Line 167: Added reference to both doc/path-spec.md AND doc/using.md
- Line 221: Strong call-out to doc/using.md for complete configuration
- Line 135: Clear reference to doc/install.md for other clients

## Verification

All documentation file references verified:
- ✅ doc/about.md
- ✅ doc/install.md
- ✅ doc/using.md
- ✅ doc/library-usage.md
- ✅ doc/path-spec.md

## Note for Task 4

doc/using.md should already have most of this detailed content. This was primarily a removal of duplication, not a content move. Verify doc/using.md has:

1. Detailed ingest strategy descriptions with examples
2. Multiple path pattern examples
3. Detailed metadata filtering examples
4. Complete configuration examples
5. Advanced file watching configuration (per-source overrides)
6. Workflow examples for using the search tool

If any of the removed content is NOT in doc/using.md, it should be added there.
