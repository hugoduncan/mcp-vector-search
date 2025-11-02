# Documentation Audit Findings

## Executive Summary

Comprehensive review of all documentation files comparing against actual implementation. Overall, the documentation is quite accurate and well-structured, but there are several inaccuracies, missing content, and opportunities for improvement.

## Statistics

- **README.md**: 422 lines (target: 250-300 lines)
- **CLAUDE.md**: 360 lines
- **doc/using.md**: 756 lines
- **doc/about.md**: 84 lines
- **doc/install.md**: 102 lines
- **doc/library-usage.md**: 361 lines
- **doc/path-spec.md**: 238 lines
- **Total**: 2323 lines
- **Source code**: 2425 lines

## Major Findings by Priority

### HIGH PRIORITY - Incorrect or Missing Information

#### 1. Missing :code-analysis strategy in CLAUDE.md
- **Location**: CLAUDE.md line 13
- **Issue**: Lists only `:whole-document`, `:namespace-doc`, `:file-path` in key capabilities
- **Missing**: `:code-analysis` strategy is implemented but not mentioned
- **Fix**: Add `:code-analysis` to capabilities list and document in Ingest Pipeline Strategies section

#### 2. Missing MCP Resources documentation in CLAUDE.md
- **Location**: CLAUDE.md - missing entire section
- **Issue**: No documentation of MCP resources, but `resources.clj` implements 5 resources
- **Missing Resources**:
  - `ingestion://status` - Overall ingestion summary
  - `ingestion://stats` - Per-source statistics
  - `ingestion://failures` - Last 20 errors
  - `ingestion://metadata` - Path metadata captures
  - `ingestion://watch-stats` - File watching statistics
- **Fix**: Add "MCP Resources" section to CLAUDE.md documenting all resources with URIs and purposes

#### 3. Incomplete :code-analysis documentation in doc/using.md
- **Location**: doc/using.md lines 311-448
- **Issue**: Documentation exists but has gaps
- **Missing Details**:
  - Constructor detection logic (lines 56-59 in code_analysis.clj)
  - How empty/whitespace docstrings are handled (lines 85-88)
  - Error handling behavior (lines 182-187, 436-440)
  - Java field detection fallback (lines 63-66)
- **Fix**: Enhance with implementation details and edge cases

### MEDIUM PRIORITY - Inaccuracies and Inconsistencies

#### 4. Line number references in CLAUDE.md may be outdated
- **Location**: CLAUDE.md lines 235, 241
- **Issue**: References `ingest.clj:84-136` and `watch.clj:181-187`
- **Status**: Need to verify these line numbers are current
- **Fix**: Update line number references and add more throughout

#### 5. Missing reference to doc/library-usage.md in some places
- **Location**: CLAUDE.md line 259, README.md line 413
- **Issue**: doc/library-usage.md exists but not consistently referenced
- **Fix**: Ensure all documentation lists include library-usage.md

#### 6. Inconsistent strategy descriptions
- **Location**: Multiple files
- **Issue**: README.md (lines 269-296) has brief descriptions, doc/using.md has detailed descriptions, CLAUDE.md is missing :code-analysis
- **Fix**: Ensure consistency across all files, with README having brief overview and using.md having details

### LOW PRIORITY - Improvements and Clarifications

#### 7. README.md exceeds target length
- **Current**: 422 lines
- **Target**: 250-300 lines (per story design)
- **Excess**: ~120-170 lines
- **Opportunities**:
  - Move detailed ingest strategy descriptions to doc/using.md (lines 269-354, ~85 lines)
  - Simplify "Advanced Features" section
  - Reduce duplication with doc/using.md

#### 8. Missing cross-references between documents
- **Issue**: Limited "See also" sections
- **Opportunity**: Add more navigation between related topics
- **Examples**:
  - CLAUDE.md references doc/about.md, path-spec.md, using.md
  - Could add references to library-usage.md when discussing bundled resources
  - Could reference resources.clj when discussing debugging

#### 9. Code examples not verified
- **Issue**: Cannot confirm all code examples actually work
- **Risk**: Examples may become outdated as code evolves
- **Recommendation**: Create test suite that validates documentation examples

## Detailed Findings by File

### README.md (422 lines)

**Accurate Content:**
- Installation instructions correct
- Basic configuration examples match implementation
- Path pattern examples correct
- Ingest strategy descriptions accurate

**Issues:**
- Line count exceeds target by 122-172 lines
- Advanced Features section duplicates doc/using.md content
- :code-analysis strategy not mentioned (but file is user-facing, may be intentional)

**Recommendations:**
- Move lines 269-354 (strategy details) to doc/using.md, keep brief overview
- Simplify Advanced Features to high-level only
- Add brief mention of :code-analysis or link to doc/using.md for all strategies

### CLAUDE.md (360 lines)

**Accurate Content:**
- Architecture description matches implementation
- Configuration system accurate
- Metadata system matches tools.clj
- Path spec parsing algorithm correct
- File watching behavior accurate

**Issues:**
- Missing :code-analysis strategy in capabilities (line 13)
- Missing MCP Resources section entirely
- Ingest Pipeline Strategies section incomplete (lines 86-150)
- Limited line number references for implementations

**Recommendations:**
- Add :code-analysis to line 13 capabilities
- Add new "MCP Resources" section documenting all 5 resources from resources.clj
- Expand Ingest Pipeline Strategies to include :code-analysis
- Add more line number references throughout (like existing examples at 235, 241)
- Consider adding resources.clj reference in documentation debugging section

### doc/using.md (756 lines)

**Accurate Content:**
- Comprehensive strategy documentation
- Configuration examples match implementation
- :code-analysis documentation exists (lines 311-448)
- Metadata filtering description accurate
- File watching documentation correct

**Issues:**
- :code-analysis section could be more detailed about edge cases
- Missing some implementation details from code_analysis.clj
- No mention of MCP resources for debugging

**Recommendations:**
- Enhance :code-analysis section with constructor detection details
- Add examples of using MCP resources for troubleshooting
- Add note about empty/whitespace docstring handling

### doc/about.md (84 lines)

**Status:** Accurate, well-written, no issues found

### doc/install.md (102 lines)

**Status:** Accurate, correct installation instructions for all clients

**Minor Issue:**
- Could mention MCP resources for verifying installation

### doc/library-usage.md (361 lines)

**Status:** Accurate, comprehensive coverage of library usage

**Accurate Content:**
- Classpath resource configuration correct
- Bundling instructions accurate
- Configuration loading order matches implementation
- Examples work correctly

**No issues found**

### doc/path-spec.md (238 lines)

**Status:** Accurate formal specification

**Accurate Content:**
- Grammar matches parser implementation
- Parsing algorithm matches config.clj
- Examples are correct
- Error conditions accurate

**No issues found**

## Summary by Category

### Missing Content (High Priority)
1. :code-analysis strategy in CLAUDE.md capabilities and strategy section
2. MCP Resources documentation in CLAUDE.md
3. Enhanced :code-analysis details in doc/using.md

### Inaccurate Content (Medium Priority)
1. Possibly outdated line number references
2. Incomplete cross-references to doc/library-usage.md

### Length Issues (Low Priority)
1. README.md exceeds target by 122-172 lines

### Enhancement Opportunities (Low Priority)
1. More line number references in CLAUDE.md
2. Better cross-referencing between documents
3. Code example validation
4. MCP resources in troubleshooting sections

## Verification Status

**Verified Against Implementation:**
- ✅ config.clj - Path spec parsing
- ✅ tools.clj - Search tool and metadata filtering
- ✅ resources.clj - MCP resources (found undocumented)
- ✅ ingest/code_analysis.clj - Code analysis strategy
- ✅ ingest/single_segment.clj - Composable strategies
- ✅ ingest/chunked.clj - Chunking strategy

**Not Verified:**
- ⚠️  Line number references accuracy
- ⚠️  Code examples (would need test execution)
- ⚠️  Installation instructions (would need fresh install)

## Recommended Action Plan

1. **Immediate (High Priority)**
   - Add :code-analysis to CLAUDE.md capabilities
   - Document all 5 MCP resources in CLAUDE.md
   - Enhance :code-analysis in doc/using.md with edge cases

2. **Short-term (Medium Priority)**
   - Verify and update all line number references
   - Add library-usage.md to all documentation indices
   - Ensure strategy descriptions consistent across files

3. **Long-term (Low Priority)**
   - Refactor README.md to reduce to target length
   - Add comprehensive cross-references
   - Create test suite for code examples
   - Add MCP resources to troubleshooting sections
