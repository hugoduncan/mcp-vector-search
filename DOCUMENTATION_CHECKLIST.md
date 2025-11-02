# Documentation Update Checklist

Organized checklist of issues to address, grouped by file and priority level.

## CLAUDE.md

### High Priority
- [ ] Add `:code-analysis` to key capabilities list (line 13)
- [ ] Add new "MCP Resources" section documenting:
  - [ ] `ingestion://status` - Overall ingestion summary
  - [ ] `ingestion://stats` - Per-source statistics
  - [ ] `ingestion://failures` - Last 20 errors
  - [ ] `ingestion://metadata` - Path metadata captures
  - [ ] `ingestion://watch-stats` - File watching statistics
- [ ] Add `:code-analysis` strategy to "Ingest Pipeline Strategies" section

### Medium Priority
- [ ] Verify line number references are current:
  - [ ] ingest.clj:84-136 (line 235)
  - [ ] watch.clj:181-187 (line 241)
- [ ] Add doc/library-usage.md to "Documentation Files" section (line 259)

### Low Priority
- [ ] Add more line number references for key implementations
- [ ] Add cross-reference to resources.clj for debugging
- [ ] Add "See also" section pointing to resources for troubleshooting

## README.md

### High Priority
- None (user-facing doc, missing :code-analysis may be intentional)

### Medium Priority
- [ ] Ensure consistency with doc/using.md for strategy descriptions (lines 269-296)
- [ ] Verify doc/library-usage.md is listed in Documentation section (line 413)

### Low Priority
- [ ] Consider reducing length from 422 to 250-300 lines:
  - [ ] Move detailed strategy descriptions (lines 269-354) to doc/using.md, keep brief overview
  - [ ] Simplify "Advanced Features" section to high-level only
  - [ ] Reduce duplication with doc/using.md content
- [ ] Consider brief mention or link for :code-analysis strategy

## doc/using.md

### High Priority
- [ ] Enhance :code-analysis section (lines 311-448) with:
  - [ ] Constructor detection logic details
  - [ ] Empty/whitespace docstring handling
  - [ ] Error handling behavior specifics
  - [ ] Java field detection fallback

### Medium Priority
- None

### Low Priority
- [ ] Add section on using MCP resources for troubleshooting
- [ ] Add examples of debugging with MCP resources

## doc/about.md

### All Priorities
- None - Accurate and complete

## doc/install.md

### All Priorities
- None - Accurate and complete

### Low Priority (Enhancement)
- [ ] Consider mentioning MCP resources for verifying installation

## doc/library-usage.md

### All Priorities
- None - Accurate and complete

## doc/path-spec.md

### All Priorities
- None - Accurate and complete

## Cross-Cutting Issues

### Medium Priority
- [ ] Ensure all documentation index sections include doc/library-usage.md:
  - [x] README.md (already present)
  - [ ] CLAUDE.md (missing)
- [ ] Verify strategy descriptions are consistent across:
  - [ ] README.md (brief)
  - [ ] doc/using.md (detailed)
  - [ ] CLAUDE.md (technical)

### Low Priority
- [ ] Add comprehensive cross-references between documents
- [ ] Add "See also" sections to guide readers between related topics
- [ ] Consider test suite for code examples validation

## Priority Summary

**Must Fix (High Priority): 9 items**
- CLAUDE.md: 7 items (MCP resources, :code-analysis strategy)
- doc/using.md: 2 items (:code-analysis enhancements)

**Should Fix (Medium Priority): 5 items**
- CLAUDE.md: 2 items (line numbers, library-usage ref)
- README.md: 1 item (consistency)
- Cross-cutting: 2 items (library-usage refs, consistency)

**Nice to Have (Low Priority): 12 items**
- CLAUDE.md: 3 items (more references, cross-refs)
- README.md: 4 items (length reduction)
- doc/using.md: 1 item (MCP resources)
- doc/install.md: 1 item (resources mention)
- Cross-cutting: 3 items (cross-refs, tests)

**Total: 26 checklist items**

## Recommended Execution Order

1. **Session 1**: CLAUDE.md high priority (MCP resources, :code-analysis)
2. **Session 2**: doc/using.md high priority (:code-analysis enhancements)
3. **Session 3**: Medium priority items (line numbers, cross-references)
4. **Session 4**: README.md length reduction (if desired)
5. **Session 5**: Low priority enhancements (cross-refs, additional examples)
