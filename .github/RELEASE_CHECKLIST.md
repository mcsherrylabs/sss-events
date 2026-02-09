# Release Checklist for sss-events

Use this checklist when preparing a new release to Maven Central via Sonatype OSS.

## Pre-Release (Before Tagging)

### 1. Code Quality & Testing

- [ ] All tests passing: `sbt test` returns exit code 0
- [ ] Benchmark tests passing: `sbt benchmarks/test`
- [ ] No compiler warnings in core code
- [ ] Code review completed (if applicable)

**Note**: One known flaky test: `ConditionVariableLatencyBenchmarkSpec` - P99 latency test may fail due to system load. Not a release blocker.

### 2. Test Coverage

- [ ] Coverage baseline documented in `docs/COVERAGE_BASELINE.md`
- [ ] Current coverage: **69.49% statement**, **63.83% branch** (exceeds thresholds ✅)
- [ ] Critical paths tested (subscriptions, scheduler, engine lifecycle)
- [ ] Known gaps documented with priorities

**Command to regenerate coverage**:
```bash
sbt clean coverage test coverageReport
open target/scala-3.6.4/scoverage-report/index.html
```

### 3. Documentation

- [ ] `CHANGELOG.md` updated with version number and date
- [ ] `CHANGELOG.md` Unreleased section moved to new version section
- [ ] `README.md` examples tested and accurate
- [ ] API breaking changes documented (if any)
- [ ] Scaladoc compiles without errors: `sbt doc`

### 4. Version Management

- [ ] Version bumped in `build.sbt` (line 78)
- [ ] Version follows semantic versioning:
  - **Patch** (0.0.X): Bug fixes, no breaking changes
  - **Minor** (0.X.0): New features, backwards compatible
  - **Major** (X.0.0): Breaking changes
- [ ] Git working directory clean (no uncommitted changes)

### 5. Dependencies

- [ ] All dependencies at stable versions (no SNAPSHOTs)
- [ ] Security vulnerabilities checked (if tooling available)
- [ ] Dependency versions documented in CHANGELOG if updated

### 6. Publishing Configuration

- [ ] **Central Portal is default** ✅ (confirmed in `build.sbt`)
- [ ] GitHub secrets verified to exist:
  - `CENTRAL_TOKEN_USERNAME` - Central Portal user token username
  - `CENTRAL_TOKEN_PASSWORD` - Central Portal user token password
- [ ] POM metadata complete (organization, license, SCM, developers)

**Configuration Summary**:
- **Default**: Publishes to **Central Portal** (central.sonatype.com) - NO GPG keys needed!
- **Override**: Set `PUBLISH_TO_NEXUS=true` for private Nexus
- **Command**: `sbt publishSigned sonatypeBundleRelease` (used by CI)
- **Signing**: Handled automatically by Central Portal (no local GPG management)

## Release (Publishing)

### 7. Create Release Tag

```bash
# Ensure on main branch with latest changes
git checkout main
git pull origin main

# Verify version in build.sbt matches intended release
grep 'version :=' build.sbt

# Create annotated tag (IMPORTANT: use v prefix)
git tag -a v0.0.11 -m "Release v0.0.11: [Brief description of changes]"

# Push tag (triggers GitHub Actions publish job)
git push origin v0.0.11
```

**Tag Format**: MUST use `v` prefix (e.g., `v0.0.11`, `v0.1.0`, `v1.0.0`)

### 8. Monitor CI

- [ ] Navigate to https://github.com/mcsherrylabs/sss-events/actions
- [ ] Verify `test` job passes
- [ ] Verify `publish` job starts (triggered by tag push)
- [ ] Monitor publish job logs for errors
- [ ] Check for successful `publishSigned` execution

**Expected Duration**: 2-5 minutes for full CI pipeline

### 9. Central Portal Publishing (Automated)

**Login**: https://central.sonatype.com/

**Good News**: Central Portal automates the staging/release process! ✨

The `sonatypeBundleRelease` command in CI automatically:
1. ✅ Creates bundle with all artifacts
2. ✅ Uploads to Central Portal
3. ✅ Central Portal signs artifacts (no GPG needed!)
4. ✅ Validates POM and artifacts
5. ✅ Publishes to Maven Central automatically

**Manual verification** (optional):
1. Navigate to https://central.sonatype.com/publishing
2. View "Deployments" to see publication status
3. Verify artifacts present:
   - `sss-events_3-0.0.11.jar`
   - `sss-events_3-0.0.11-sources.jar`
   - `sss-events_3-0.0.11-javadoc.jar`
   - `sss-events_3-0.0.11.pom`
   - Signatures added automatically by Central Portal

**If validation fails**:
- Check CI logs for errors
- Fix issues in code/POM
- Create new version tag (cannot reuse version)

**Warning**: Once published, the version is **immutable** - you cannot republish the same version.

### 10. Verify Maven Central Publication

- [ ] Wait 10-30 minutes for synchronization
- [ ] Search at https://search.maven.org/
  - Search: `g:com.mcsherrylabs a:sss-events_3`
  - Verify new version appears
- [ ] Check artifact page: https://central.sonatype.com/artifact/com.mcsherrylabs/sss-events_3

## Post-Release

### 11. Create Test Project

Verify published artifact works:

```bash
mkdir /tmp/sss-events-test
cd /tmp/sss-events-test

# Create minimal build.sbt
cat > build.sbt <<EOF
scalaVersion := "3.6.4"
libraryDependencies += "com.mcsherrylabs" %% "sss-events" % "0.0.11"
EOF

# Create test app
mkdir -p src/main/scala
cat > src/main/scala/Test.scala <<EOF
import sss.events.*

@main def test(): Unit = {
  val engine = EventProcessingEngine()
  engine.start()
  println("sss-events loaded successfully!")
  engine.shutdown()
}
EOF

# Run test
sbt run
```

- [ ] Test project compiles successfully
- [ ] Test project runs without errors
- [ ] Basic example from README works

### 12. GitHub Release

- [ ] Navigate to https://github.com/mcsherrylabs/sss-events/releases
- [ ] Click "Draft a new release"
- [ ] Select tag: `v0.0.11`
- [ ] Release title: `v0.0.11 - [Brief description]`
- [ ] Release notes: Copy relevant section from CHANGELOG.md
- [ ] Attach artifacts (optional): JARs, documentation
- [ ] Mark as pre-release if < v1.0.0 (optional)
- [ ] Publish release

### 13. Communication & Documentation

- [ ] Update any external documentation sites (if applicable)
- [ ] Announce release in appropriate channels (if applicable)
- [ ] Close related GitHub issues
- [ ] Update project roadmap (if maintained)

### 14. Housekeeping

- [ ] Archive completed todos from `/todos/` directory
  - Review and close resolved issues
  - Move to `todos/archive/` if desired
- [ ] Bump version to next SNAPSHOT for ongoing development:
  - Example: `version := "0.0.12-SNAPSHOT"` in build.sbt
  - Commit: `git commit -am "chore: bump version to 0.0.12-SNAPSHOT"`
  - Push: `git push origin main`

## Rollback Procedure

If a critical issue is discovered after release:

### Immediate (Before "Release" in Sonatype)
1. Do NOT click "Release" button in Sonatype
2. Click "Drop" button to discard staging repository
3. Fix issue, bump version, create new tag

### Short-term (Within 24-48 hours of release)
1. **Contact Sonatype Support** immediately
   - Email: central-support@sonatype.com
   - Include: Group ID, Artifact ID, Version
   - Request: Artifact removal from Maven Central
2. Sonatype may be able to remove if not yet widely synced
3. **Not guaranteed** - Maven Central is designed to be immutable

### Long-term (After 48+ hours)
1. **Cannot remove** from Maven Central (immutability by design)
2. **Publish patch release** with fix:
   - Bump patch version (e.g., 0.0.11 → 0.0.12)
   - Document issue in CHANGELOG.md
   - Add "Known Issues" section to GitHub release notes of problematic version
3. Deprecate problematic version if possible (check Maven Central tools)

### Critical Failure Examples
- **Security vulnerability**: Publish emergency patch ASAP
- **Data corruption bug**: Publish patch + clear warning in docs
- **Breaking API change**: Apologize, publish patch restoring compatibility or bumping major version

## Emergency Contacts

- **Sonatype Support**: central-support@sonatype.com
- **Project Maintainer**: (Add your contact info)
- **CI/CD Issues**: Check GitHub Actions status page

## Additional Resources

### Documentation
- Sonatype OSS Guide: https://central.sonatype.org/publish/publish-guide/
- sbt Publishing: https://www.scala-sbt.org/1.x/docs/Publishing.html
- Semantic Versioning: https://semver.org/

### Internal Documentation
- Architecture: `docs/` directory
- Test Coverage: `docs/COVERAGE_BASELINE.md`
- Performance: `PERFORMANCE_BENCHMARKS.md`
- Contributing: (Add CONTRIBUTING.md if created)

### Known Issues & Limitations

**Current Test Coverage Gaps** (documented in `docs/COVERAGE_BASELINE.md`):
- P0: Subscriptions NotDelivered handling (when queue full)
- P0: Scheduler FailedQueueFull scenario (when queue full)
- P1: Concurrent subscribe/unsubscribe during broadcast
- P1: Scheduler cancel during execution race condition

These gaps are **documented but not release blockers** - coverage exceeds thresholds (69% statement, 63% branch).

**Flaky Tests**:
- `ConditionVariableLatencyBenchmarkSpec` - P99 latency assertion sensitive to system load
  - **Not a release blocker** - performance benchmark, not functional test
  - May fail in CI under heavy load (threshold: 200μs, typical: 180-250μs)

## Checklist Maintenance

This checklist should be updated when:
- Publishing process changes
- New quality gates added
- Secrets or credentials rotated
- Sonatype process updated
- Critical lessons learned from previous releases

**Last Updated**: 2026-02-09
**Version**: 0.0.10 (pre-release)
