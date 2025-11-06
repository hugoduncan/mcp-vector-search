# Releasing mcp-vector-search

This document describes the release process for mcp-vector-search.

## Prerequisites

- You must be on the `main` branch
- All tests must pass (CI checks this automatically)
- Required repository secrets must be configured:
  - `CLOJARS_USERNAME` - Your Clojars username
  - `CLOJARS_PASSWORD` - Your Clojars deploy token (not your login password)

To configure secrets, go to repository Settings → Secrets and variables → Actions.

## Version Scheme

Releases use a `major.minor.commit-count` version format (e.g., `0.1.42`).

- `major.minor` is defined in `dev/build.clj` (currently `0.1`)
- `commit-count` is automatically calculated from git commit history
- Full version is computed at release time: `0.1.42` means version base `0.1` with 42 commits

The version base should be incremented manually when introducing breaking changes or major features.

## Release Process

### 1. Trigger the Release Workflow

Go to the Actions tab in GitHub and select the "Release" workflow. Click "Run workflow" and choose:

- **dry-run**: `false` (uncheck for actual release, check for testing)

### 2. What Happens During Release

The workflow automatically:

1. **Calculates version** from git commit count
2. **Builds JAR** using `clojure -T:build jar`
3. **Runs smoke test** to validate JAR structure
4. **Generates changelog** using git-cliff (conventional commits)
5. **Creates git tag** (format: `vX.Y.Z`)
6. **Deploys to Clojars** as `org.hugoduncan/mcp-vector-search`
7. **Creates GitHub release** with:
   - Generated changelog
   - JAR artifact attached
   - Tag reference

If dry-run is enabled, steps 5-7 are skipped (no tags, no deployment, no GitHub release).

## Testing the Release Process (Dry Run)

To verify the release process without actually releasing:

1. Go to Actions → Release workflow
2. Click "Run workflow"
3. Set **dry-run** to `true` (check the box)
4. Click "Run workflow"

This will:
- Calculate the version
- Build the JAR
- Run smoke tests
- Generate the changelog
- Stop before creating tags, deploying, or creating the release

Review the workflow logs to verify everything works correctly.

## Changelog Generation

Changelogs are automatically generated from conventional commit messages using git-cliff.

### Commit Message Format

Use conventional commit format for automatic changelog grouping:

```
<type>(<optional scope>): <description>

[optional body]

[optional footer]
```

**Supported types** (from `cliff.toml`):
- `feat` → Features
- `fix` → Bug Fixes
- `perf` → Performance
- `refactor` → Refactoring
- `docs` → Documentation
- `test` → Testing
- `build` → Build System
- `ci` → CI/CD
- `chore` → Chores
- `style` → Code Style
- `revert` → Reverts

**Examples:**
```
feat(search): add metadata filtering support
fix(ingest): handle symlinks correctly in path normalization
docs: update installation guide with Java version requirements
```

### Excluded from Changelog

The following commits are automatically filtered out:
- Commits with scope `release` or `changelog`
- Commits containing `[skip changelog]` or `[changelog skip]`
- Merge commits from `local-integration-branch`

## After Release

After a successful release:

1. **Verify Clojars deployment**: Check https://clojars.org/org.hugoduncan/mcp-vector-search
2. **Verify GitHub release**: Check the Releases page for the new tag and attached JAR
3. **Update README.md**: If the installation instructions reference a specific SHA, update to use the new version/tag
4. **Announce**: Update any relevant documentation or announcements

## Troubleshooting

### "No previous tag found" warning

This is normal for the first release. git-cliff will generate a full changelog from all commits.

### Deployment fails with authentication error

Verify that `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` secrets are correctly configured. The password must be a deploy token from Clojars, not your account password.

### JAR smoke test fails

The smoke test validates:
- JAR file exists at expected location
- pom.xml is present in META-INF
- Namespace files are included

Check the workflow logs for specific validation failures.

### Version number seems wrong

Version is calculated from git commit count. Ensure you have full git history (the workflow uses `fetch-depth: 0` to get all commits).

## Manual Release (Not Recommended)

For emergency releases or testing, you can release manually:

```bash
# 1. Calculate and display version
clojure -T:build version

# 2. Build JAR
clojure -T:build jar

# 3. Deploy to Clojars (requires CLOJARS_USERNAME and CLOJARS_PASSWORD env vars)
clojure -T:build deploy
clojure -X:deploy

# 4. Create and push tag
VERSION=$(clojure -T:build version | grep "Version:" | awk '{print $2}')
git tag -a "v$VERSION" -m "Release v$VERSION"
git push origin "v$VERSION"

# 5. Create GitHub release manually via web UI
```

However, using the GitHub Actions workflow is strongly preferred for consistency and automatic changelog generation.
