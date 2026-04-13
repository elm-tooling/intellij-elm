Releasing The Elm Plugin To The JetBrains Marketplace
=====================================================

This project publishes to JetBrains Marketplace with Gradle and uses plugin code signing.

The Gradle setup is already configured in [`build.gradle.kts`](../build.gradle.kts):

- `signPlugin` reads:
  - `CERTIFICATE_CHAIN`
  - `PRIVATE_KEY`
  - `PRIVATE_KEY_PASSWORD`
- `publishPlugin` reads:
  - `PUBLISH_TOKEN`

## One-time setup

1. Create a Marketplace publishing token (`PUBLISH_TOKEN`).
2. Create a signing certificate/key pair for JetBrains plugin signing.
3. Store all four values in GitHub repository secrets for CI:
   - `PUBLISH_TOKEN`
   - `CERTIFICATE_CHAIN`
   - `PRIVATE_KEY`
   - `PRIVATE_KEY_PASSWORD`

Reference:
- https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
- https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html

## Local release verification

You can validate signing locally before creating a release:

```bash
./gradlew buildPlugin --no-daemon
./gradlew signPlugin --no-daemon
./gradlew verifyPluginSignature --no-daemon
```

Artifacts are generated under:

- `build/distributions/` (plugin ZIP)
- `build/distributions/*-signed.zip` (signed ZIP, after `signPlugin`)

## CI release flow

Publishing is handled by [`.github/workflows/release.yml`](../.github/workflows/release.yml):

1. Build plugin ZIP.
2. Sign plugin ZIP.
3. Verify plugin signature.
4. Publish plugin to Marketplace.

The workflow intentionally does not upload plugin ZIP files as GitHub release assets.
This avoids the current zip-in-zip artifact issue in release attachments.

Do not commit signing keys/certificates to the repository. Keep them only in CI secrets.
