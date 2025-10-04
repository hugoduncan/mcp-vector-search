# File Watching Testing

## Overview

File watching functionality uses the hawk library for cross-platform file system event monitoring. Due to hawk's async behavior and platform-specific timing, automated integration tests are unreliable.

## Automated Tests

Unit tests (`watch_test.clj`) verify:
- Watch configuration (global and per-source `:watch?`)
- Path matching against patterns (literals, globs, captures)
- Recursive watch detection based on path specs

All unit tests pass reliably across platforms.

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
- Uses Barbary WatchService
- `/var` is symlinked to `/private/var` - handled by path normalization
- File events typically arrive within 1-2 seconds

### Linux
- Uses Java NIO WatchService
- Generally faster event delivery than macOS

### Windows
- Uses Java NIO WatchService
- Not extensively tested but should work

## Timing Characteristics

- **Debounce window**: 500ms per file
- **Event latency**: Platform-dependent, typically 1-3 seconds
- **Watch setup**: Nearly instantaneous

Hawk's latency varies by platform and watcher implementation. The polling fallback has configurable sensitivity (`:high`, `:medium`, `:low`) but exact timings are undocumented.
