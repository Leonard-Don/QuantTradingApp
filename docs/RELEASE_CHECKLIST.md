# TianXianQuant Release Checklist

## Internal Demo APK

- [x] `TianXianQuant/scripts/verify_p0.sh` passes.
- [x] `TianXianQuant/scripts/verify_emulator_smoke.sh` passes.
- [x] Debug payment simulation is isolated behind `ALLOW_LOCAL_PAYMENT_SIMULATION`.
- [x] Release build disables local VIP activation.
- [x] Release build enables R8 minify and resource shrink.
- [x] App states that reports are research references, not investment advice.
- [x] Quant backtests use historical daily K-line samples instead of static demo metrics.

## Store Test Release

- [x] Backend contract scaffold covered by local pytest.
- [x] Release APK/AAB artifact script exists.
- [x] Android auth/VIP flows have a build-flagged backend sync path.
- [ ] Configure signing properties outside git.
- [ ] Build signed AAB.
- [ ] Prepare app icon, screenshots, feature graphic, short description, and full description.
- [ ] Add privacy policy URL.
- [ ] Add user agreement URL.
- [ ] Add data-source disclaimer.
- [ ] Validate notification permission copy.
- [ ] Validate account deletion/support contact path.
- [x] Wire Android account deletion UI to `DELETE /v1/me` and local data cleanup.
- [ ] Confirm target SDK policy before upload.

## Paid Launch

- [ ] Server account system live.
- [ ] Server entitlement API live.
- [ ] Android backend sync enabled against deployed production API.
- [ ] WeChat/Alipay merchant sandbox verified.
- [ ] Backend payment callback verification live.
- [ ] Refund/cancel policy implemented.
- [ ] Licensed data provider contract signed.
- [ ] Premium endpoints proxied by backend.
- [ ] Security review for local cache, tokens, and entitlement grace period.
- [ ] Monitoring for order failures, API failures, crashes, and app startup.

## Manual QA

- [ ] Fresh install.
- [ ] Upgrade install from previous schema.
- [ ] Offline launch after cached quote exists.
- [ ] VIP expired state.
- [ ] VIP active state.
- [ ] Notification permission denied.
- [ ] Notification permission granted.
- [ ] Account deletion clears local watchlist, holdings, posts, strategies, snapshots, quote cache, and server account when backend sync is enabled.
- [ ] Quant historical backtest with a 6-digit stock code and one-year date range.
- [ ] Large font / accessibility text scale.
- [ ] Tablet layout.

## Build Commands

```bash
cd TianXianQuant
scripts/verify_p0.sh
scripts/verify_emulator_smoke.sh
scripts/build_release_artifacts.sh

# from repository root
scripts/verify_backend.sh
scripts/verify_all.sh

# backend-sync debug build
cd TianXianQuant
./gradlew :app:assembleDebug \
  -PtianxianBackendSyncEnabled=true \
  -PtianxianApiBaseUrl=http://10.0.2.2:8080/
```
