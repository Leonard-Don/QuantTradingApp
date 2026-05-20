# QuantTradingApp Release Candidate Summary

Last reviewed: 2026-05-02

## Current Local Candidate

The project is ready for internal engineering and store-test preparation, but not ready for public paid launch until external production resources are configured.

## Completed Locally

- Backend contract tests cover auth, token refresh, entitlements, order status, refunds/cancels, payment callback signature checks, user order history, premium data proxy placeholder, account deletion, and admin audit access control.
- Android P0 verification covers XML validation, unit tests, lint, debug build, and release APK generation.
- Release artifact script builds APK and AAB.
- QA backend debug build can require service-side payment/entitlement sync and block local VIP fallback.
- VIP page shows cached subscription-order history and can refresh backend order state.
- Backend has a token-protected read-only admin audit JSON and HTML surface.
- Release signing is wired through external environment variables and ignored local secret files.
- Store listing, manual QA matrix, privacy policy draft, user agreement draft, feature graphic generation, and emulator screenshot capture workflow exist.

## External Gates Still Required

- Real backend deployment with persistent storage and production secrets.
- Real public privacy policy, user agreement, and data-source disclaimer URLs.
- Real support email and service entity information.
- Upload signing keystore and passwords stored outside git.
- Signed AAB built from the final store-test candidate.
- WeChat/Alipay or app-store billing merchant sandbox verification.
- Licensed premium data provider credentials and contract.
- Final real-device QA evidence and store screenshots captured from the signed candidate.

## Commands

```bash
scripts/prepare_store_candidate.sh
scripts/capture_store_screenshots.sh

set -a
source release.env
set +a
scripts/verify_paid_release_config.sh
QuantTradingApp/scripts/build_release_artifacts.sh
```

The first command is the local engineering gate. The later commands are the paid/store gates and intentionally require real external values.
