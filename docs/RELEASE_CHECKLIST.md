# TianXianQuant Release Checklist

## Internal Demo APK

- [x] `TianXianQuant/scripts/verify_p0.sh` passes.
- [x] `TianXianQuant/scripts/verify_emulator_smoke.sh` passes.
- [x] Debug payment simulation is isolated behind `ALLOW_LOCAL_PAYMENT_SIMULATION`.
- [x] Release build disables local VIP activation.
- [x] Release build enables R8 minify and resource shrink.
- [x] App states that reports are research references, not investment advice.

## Store Test Release

- [x] Backend contract scaffold covered by local pytest.
- [x] Release APK/AAB artifact script exists.
- [ ] Configure signing properties outside git.
- [ ] Build signed AAB.
- [ ] Prepare app icon, screenshots, feature graphic, short description, and full description.
- [ ] Add privacy policy URL.
- [ ] Add user agreement URL.
- [ ] Add data-source disclaimer.
- [ ] Validate notification permission copy.
- [ ] Validate account deletion/support contact path.
- [ ] Wire Android account deletion UI to `DELETE /v1/me`.
- [ ] Confirm target SDK policy before upload.

## Paid Launch

- [ ] Server account system live.
- [ ] Server entitlement API live.
- [ ] Android auth/VIP flows integrated with server account and entitlement APIs.
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
```
