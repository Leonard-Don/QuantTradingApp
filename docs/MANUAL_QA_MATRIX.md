# QuantTradingApp Manual QA Matrix

Last reviewed: 2026-05-02

This matrix is the human QA sheet for store-test and paid-release candidates. Automated gates prove build, lint, unit contracts, release artifacts, backend contracts, and emulator smoke; this sheet covers stateful and visual flows that still need hands-on confirmation on real devices.

## Build Under Test

- Git commit:
- APK/AAB path:
- Backend base URL:
- Backend sync required: yes/no
- Tester:
- Device / OS:
- Date:

## Account And Entitlement

| Case | Steps | Expected | Result |
| --- | --- | --- | --- |
| Fresh install | Install app, launch, open account page | Logged-out state, local sync notice, no crash | |
| Register | Register with valid phone/password | Local or backend account created, account card refreshes | |
| Login | Logout and login with same account | Account state restores | |
| Manual entitlement refresh | Tap sync entitlements | Status updates without crash | |
| VIP inactive | Open VIP-only feature before payment | Locked copy explains subscription and no advice boundary | |
| VIP active | Complete debug/QA sandbox payment | Correct tier active, expiry shown | |
| Order history | Complete or refresh a subscription | VIP page shows recent order status, amount, channel, tier, and source | |
| Backend required payment failure | Stop backend, run QA backend build, attempt subscription | No local fallback activation; status explains backend failure | |
| Account deletion | Add watchlist/post/strategy/cache, delete account | Local data cleared; backend account deleted when sync enabled | |

## Payment And Refund States

| Case | Steps | Expected | Result |
| --- | --- | --- | --- |
| Debug local simulation | Default debug build, subscribe | VIP activates locally, copy says validation payment | |
| Backend sandbox paid | QA backend build with backend running, subscribe | Backend order callback activates entitlement | |
| Pending payment | Disable callback or use production build without merchant config | No VIP activation; pending/unconfigured copy visible | |
| Refund callback | Trigger backend `REFUNDED`, refresh entitlements | Entitlement reduced/revoked per backend policy | |
| Cancel callback | Trigger backend `CANCELLED` for pending order | Order closes without entitlement activation | |

## Core Product Flows

| Case | Steps | Expected | Result |
| --- | --- | --- | --- |
| Stock search | Search 6-digit code and keyword | Quote/status text updates; no fake search result on source failure | |
| Watchlist | Add/remove stock, open watchlist filter | State persists across app restart | |
| Review snapshot | Refresh sample snapshot | Snapshot list updates; disclaimer visible in locked flows | |
| Portfolio holding | Add holding and refresh | Floating P/L and risk tags render; no broker/trading wording | |
| Community post/comment | Add post and comment | Local content persists; compliance notice shown | |
| Quant backtest | Run one-year backtest for a 6-digit code | Historical K-line result or clear source failure | |
| Custom model | Create formula model | Allowed formula persists and appears at top | |

## Permissions And Accessibility

| Case | Steps | Expected | Result |
| --- | --- | --- | --- |
| Notification denied | Deny permission, tap daily reminder | Disabled/missing-permission state visible | |
| Notification granted | Grant permission, send test reminder | Local notification appears | |
| Large font | Set system font large and inspect main tabs/VIP/account | Text remains readable without overlap | |
| Tablet | Run on sw600dp or tablet emulator | Stock layout uses tablet resources, no clipped controls | |
| Offline after cache | Load quotes, disable network, relaunch | Cache warning shows timestamp and source | |

## Exit Criteria

- No crashes in crash buffer or logcat during tested flows.
- No paid entitlement activates in release/unconfigured builds.
- QA backend build does not fall back to local VIP when backend payment sync is required.
- Account deletion removes local state and server state.
- All store-facing copy avoids investment-advice, guaranteed-return, and trading-instruction language.
