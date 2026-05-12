# Release Signing And Store Candidate Setup

Last reviewed: 2026-05-07

This project can build unsigned release artifacts locally. A store-test or paid-release candidate must be signed with an upload key that is kept outside git.

## Local Secret File

Copy the template and fill it with real production values:

```bash
cp release.env.example release.env
```

`release.env` is ignored by git. Keep the keystore and passwords in a password manager or CI secret store.

## Required Signing Values

```bash
export TIANXIAN_RELEASE_KEYSTORE=/secure/path/tianxian-upload.jks
export TIANXIAN_RELEASE_STORE_PASSWORD=<store-password>
export TIANXIAN_RELEASE_KEY_ALIAS=tianxian-upload
export TIANXIAN_RELEASE_KEY_PASSWORD=<key-password>
```

The Gradle release build reads these values through project properties. `TianXianQuant/scripts/build_release_artifacts.sh` and `scripts/verify_paid_release_config.sh` pass them automatically when the environment variables are set.

The wrappers intentionally translate `TIANXIAN_RELEASE_*` values into `ORG_GRADLE_PROJECT_tianxianRelease*` environment variables. Do not pass signing values with Gradle's `-P` command-line option; argv can be echoed by CI logs, shell history, or process inspection. Keep release-signing examples in this file limited to environment variables and synthetic placeholders.

## Build A Signed Candidate

```bash
set -a
source release.env
set +a

scripts/verify_paid_release_config.sh
TianXianQuant/scripts/build_release_artifacts.sh
```

Expected outputs:

- `TianXianQuant/app/build/outputs/apk/release/*.apk`
- `TianXianQuant/app/build/outputs/bundle/release/*.aab`

Without signing values, the local build still produces unsigned release artifacts for engineering validation. That is useful for CI but not acceptable for store upload.

## Production Gate

The paid/store gate requires:

- backend sync enabled
- backend payment sync required
- deployed production API URL
- privacy policy URL
- user agreement URL
- data disclaimer URL
- support email
- complete release signing properties

The gate intentionally fails until those external values are real.
