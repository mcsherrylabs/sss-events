# Central Portal Migration Guide

**Date**: 2026-02-09
**Status**: Migration Complete - Awaiting Central Portal Account Setup

## What Changed

Migrated from Legacy Sonatype OSSRH (with manual GPG key management) to **Sonatype Central Portal** (with automated signing).

### Benefits ✨

1. **No GPG Key Management**: Central Portal signs artifacts for you
2. **Simpler CI**: Removed 15 lines of GPG configuration from GitHub Actions
3. **Token-Based Auth**: Use user tokens instead of username/password
4. **Automatic Release**: No manual "Close" and "Release" steps
5. **Modern API**: Better performance and reliability

### What You Lost

- Nothing! Central Portal is strictly better for publishing open source libraries.

## Setup Steps (One-Time)

### 1. Create Central Portal Account

**Navigate to**: https://central.sonatype.com/

**Sign up options**:
- GitHub OAuth (recommended)
- Google OAuth
- Email/password

**Create account** and verify email.

### 2. Claim Namespace

Central Portal requires namespace verification for publishing.

**Your namespace**: `com.mcsherrylabs`

**Verification options**:
- **Option A (Recommended)**: GitHub repository verification
  1. Go to https://central.sonatype.com/publishing/namespaces
  2. Add namespace: `com.mcsherrylabs`
  3. Select "Verify via GitHub"
  4. It will check that you own `github.com/mcsherrylabs`
  5. Instant verification ✅

- **Option B**: DNS TXT record
  1. Add TXT record to `mcsherrylabs.com` domain
  2. Record: `central.sonatype.com=<verification-code>`
  3. Wait for DNS propagation (~1 hour)

**Status Check**:
```bash
# After verification, you should see your namespace approved
# Check at: https://central.sonatype.com/publishing/namespaces
```

### 3. Generate Publishing Token

**Navigate to**: https://central.sonatype.com/account

1. Click **"Generate User Token"**
2. Copy the username (looks like: `RaNd0m-St1nG`)
3. Copy the password (looks like: `Ev3nM0r3-Rand0m-Ch4rs`)
4. **Save these securely** - you won't see them again!

### 4. Update GitHub Secrets

**Navigate to**: https://github.com/mcsherrylabs/sss-events/settings/secrets/actions

**Add these secrets**:

| Secret Name | Value | Description |
|-------------|-------|-------------|
| `CENTRAL_TOKEN_USERNAME` | `<your-token-username>` | From step 3 above |
| `CENTRAL_TOKEN_PASSWORD` | `<your-token-password>` | From step 3 above |

**Remove these old secrets** (no longer needed):
- ~~`PGP_SECRET`~~ ❌
- ~~`PGP_PASSPHRASE`~~ ❌
- ~~`SONA_USER`~~ ❌
- ~~`SONA_PASS`~~ ❌

### 5. Test Publishing (Local Dry Run)

Before tagging a release, test locally:

```bash
# Set credentials temporarily
export CENTRAL_TOKEN_USERNAME="your-username-here"
export CENTRAL_TOKEN_PASSWORD="your-password-here"

# Test bundle creation (doesn't publish)
sbt publishSigned

# Check bundle was created
ls -la target/sonatype-staging/
```

**Expected output**:
```
target/sonatype-staging/0.0.10/
├── sss-events_3-0.0.10.jar
├── sss-events_3-0.0.10-sources.jar
├── sss-events_3-0.0.10-javadoc.jar
└── sss-events_3-0.0.10.pom
```

**Note**: Local test won't actually publish - only CI will publish on tag push.

## Code Changes Made

### build.sbt

**Before** (Legacy OSSRH):
```scala
usePgpKeyHex("F4ED23D42A612E27F11A6B5AF75482A04B0D9486"),
publishTo := {
  val sonaUrl = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at sonaUrl + "content/repositories/snapshots")
  else
    Some("releases" at sonaUrl + "service/local/staging/deploy/maven2")
}
```

**After** (Central Portal):
```scala
// Central Portal handles signing - no GPG key needed
publishTo := sonatypePublishToBundle.value,
sonatypeCredentialHost := "central.sonatype.com",
sonatypeRepository := "https://central.sonatype.com/api/v1/publisher",
```

### GitHub Actions Workflow

**Before** (20+ lines of GPG setup):
```yaml
- name: Load Variables
  run: |
    echo 'PGP_SECRET<<EOF' >> $GITHUB_ENV
    echo '${{ secrets.PGP_SECRET }}' >> $GITHUB_ENV
    echo 'EOF' >> $GITHUB_ENV
    # ... more GPG config ...
- name: Configure GPG Key
  run: |
    mkdir -p ~/.gnupg/
    # ... 10 more lines ...
    gpg --import --no-tty --batch --yes ~/.gnupg/private.key
- name: Publish to Sonatype
  run: sbt publishSigned
```

**After** (Simple token auth):
```yaml
- name: Publish to Central Portal
  run: sbt publishSigned sonatypeBundleRelease
  env:
    CENTRAL_TOKEN_USERNAME: ${{ secrets.CENTRAL_TOKEN_USERNAME }}
    CENTRAL_TOKEN_PASSWORD: ${{ secrets.CENTRAL_TOKEN_PASSWORD }}
```

## Publishing Workflow (New Process)

### Before (Legacy OSSRH)
1. Tag release → CI builds → Publishes to staging
2. **Manual**: Login to oss.sonatype.org
3. **Manual**: Find staging repository
4. **Manual**: Click "Close" → Wait for validation
5. **Manual**: Click "Release" → Publish to Maven Central
6. Wait 10-30 minutes for sync

### After (Central Portal) ✨
1. Tag release → CI builds → **Automatically publishes to Maven Central**
2. Done! 🎉
3. Wait 10-30 minutes for sync

**Commands**:
```bash
# Create and push tag
git tag -a v0.0.11 -m "Release v0.0.11"
git push origin v0.0.11

# CI automatically:
# 1. Builds artifacts
# 2. Central Portal signs them
# 3. Validates POM
# 4. Publishes to Maven Central
# 5. Done!
```

## Troubleshooting

### Error: "Namespace not verified"

**Symptom**: CI fails with "You do not have permission to publish to com.mcsherrylabs"

**Solution**:
1. Go to https://central.sonatype.com/publishing/namespaces
2. Add and verify `com.mcsherrylabs` namespace
3. Use GitHub verification (fastest)

### Error: "Invalid credentials"

**Symptom**: CI fails with authentication error

**Solution**:
1. Verify GitHub secrets are set correctly
2. Regenerate user token if needed at https://central.sonatype.com/account
3. Update secrets with new token

### Error: "POM validation failed"

**Symptom**: CI succeeds but Central Portal rejects artifact

**Solution**:
Check POM requirements at https://central.sonatype.org/publish/requirements/
- ✅ Group ID matches verified namespace
- ✅ License specified
- ✅ SCM URL specified
- ✅ Developer info specified

All requirements already met in current `build.sbt` ✅

### Warning: "Bundle not found"

**Symptom**: `sonatypeBundleRelease` can't find bundle

**Solution**:
Ensure `publishSigned` runs before `sonatypeBundleRelease`:
```bash
sbt publishSigned sonatypeBundleRelease
```
(Already correct in CI workflow ✅)

## Verification Checklist

After setup, verify:

- [ ] Central Portal account created
- [ ] Namespace `com.mcsherrylabs` verified
- [ ] User token generated
- [ ] GitHub secrets `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` set
- [ ] Old GPG secrets removed (optional cleanup)
- [ ] Local dry run successful (`sbt publishSigned`)
- [ ] Ready to tag and release!

## Next Steps

1. ✅ Code changes committed
2. ⏳ **You need to**: Complete Central Portal account setup (steps 1-4 above)
3. ⏳ Test release with tag: `v0.0.11`
4. ⏳ Verify on Maven Central: https://search.maven.org/

## Support Resources

- **Central Portal Docs**: https://central.sonatype.org/publish/publish-portal-upload/
- **Namespace Verification**: https://central.sonatype.org/publish/requirements/namespaces/
- **Support**: https://central.sonatype.org/support/

## Migration Date

- **Committed**: 2026-02-09
- **Status**: Awaiting account setup
- **First Release**: TBD (after Central Portal account configured)
