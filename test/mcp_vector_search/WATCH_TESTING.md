# File Watching Testing

## Overview

File watching functionality uses the beholder library for cross-platform file system event monitoring. Due to beholder's event timing and our debouncing logic, automated integration tests are unreliable.

## Automated Tests

### Unit Tests (`watch_test.clj`)
Verify core watch logic:
- Watch configuration (global and per-source `:watch?`)
- Path matching against patterns (literals, globs, captures)
- Recursive watch detection based on path specs

**25 assertions, all passing**

### Integration Tests (`watch_integration_test.clj`)
Test re-indexing logic without beholder timing dependencies:
- Re-ingesting modified files (simulating MODIFY events)
- Removing deleted files (simulating DELETE events)
- Adding newly created files (simulating CREATE events)
- Handling multiple rapid changes (simulating debouncing)

These tests directly call the ingestion logic that watch event handlers
would trigger, verifying the behavior without relying on beholder's async
event delivery.

**7 assertions, all passing**

Our integration tests avoid timing challenges by testing the logic
independently of the watch mechanism.

## Manual Testing

For interactive verification of watch functionality, use the manual test script:

```bash
clojure -M -m mcp-vector-search.manual-watch-test
```

This will:
1. Create a temporary test directory
2. Start file watches
3. Display the current embedding count every 2 seconds
4. Wait for you to manually create/modify/delete `.md` files

### Test Scenarios

While the manual test is running:

**Test CREATE events:**
```bash
echo "New content" > /path/to/test-dir/new.md
```
Watch for embedding count to increase.

**Test MODIFY events:**
```bash
echo "Modified content" > /path/to/test-dir/new.md
```
Watch for re-indexing (count stays the same, content updated).

**Test DELETE events:**
```bash
rm /path/to/test-dir/new.md
```
Watch for embedding count to decrease.

**Test debouncing:**
```bash
for i in {1..5}; do echo "Change $i" > /path/to/test-dir/test.md; sleep 0.1; done
```
Watch should batch these changes (single re-index after 500ms).

Press Ctrl+C to exit and clean up.

## Platform Notes

### macOS
- Uses JNA-based native WatchService for better performance
- `/var` is symlinked to `/private/var` - handled by path normalization
- File events typically arrive within 1-2 seconds

### Linux
- Uses directory-watcher for accurate recursive watching
- Generally fast and reliable event delivery

### Windows
- Uses directory-watcher for accurate recursive watching
- Not extensively tested but should work

## Timing Characteristics

- **Debounce window**: 500ms per file
- **Event latency**: Platform-dependent, typically 1-3 seconds
- **Watch setup**: Nearly instantaneous

Beholder uses the directory-watcher Java library which provides accurate and efficient recursive watching across all platforms.
