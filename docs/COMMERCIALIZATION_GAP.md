# TianXianQuant Commercialization Gap

Last reviewed: 2026-04-29

## Current Status

TianXianQuant is ready for local MVP demos and internal Android testing. It is not ready for real paid subscriptions until account, payment, entitlement, and data-provider responsibilities move out of the local app boundary.

## Go / No-Go

| Area | Status | Decision |
| --- | --- | --- |
| Android build, lint, debug/release APK | Passing through `TianXianQuant/scripts/verify_p0.sh` | Go for internal testing |
| Emulator smoke flow | Passing through `TianXianQuant/scripts/verify_emulator_smoke.sh` | Go for internal testing |
| Subscription value surfaces | Implemented as local research tools | Go for MVP validation |
| Real payments | Backend sandbox order/callback scaffold exists; merchant callbacks not connected | No-go for paid launch |
| Server-side entitlement | Backend source-of-truth scaffold exists; Android has a build-flagged sync path but production API is not deployed | No-go for paid launch |
| Market data authorization | Public/free sources only | No-go for premium data claims |
| Privacy/legal artifacts | Drafts only | No-go for store submission |
| Release signing/AAB | Not configured | No-go for store submission |

## Must Finish Before Paid Launch

1. Deploy the backend scaffold, enable Android backend sync for QA/Release builds, and stop treating Room as the production source of truth.
2. Replace sandbox callback with production WeChat/Alipay or Google Play Billing verification.
3. Deploy server-side VIP entitlement source of truth with app-side cached entitlement and grace period.
4. Add merchant SDK integration, callback verification, refund/cancel policy, and admin audit workflow.
5. Licensed market-data provider contract for premium claims such as main capital flow, full-market coverage, 龙虎榜, financial indicators, and valuation percentiles.
6. Privacy policy, user agreement, paid subscription rules, data-source disclaimer, and app-store metadata.
7. Release signing, AAB build, versioning policy, and rollback plan.

## Can Ship As Internal Demo

- Multi-source public quote/K-line fallback and local quote cache.
- Local watchlist, stock diagnosis, watchlist health, review history, quant diagnosis, community digest.
- Debug-only local payment simulation.
- Local backend scaffold for accounts, orders, sandbox callbacks, entitlement, account deletion, and premium data proxy contracts.
- Build-flagged Android backend sync for login, entitlement refresh, and Debug sandbox subscription verification.
- Repeatable local verification scripts and GitHub Actions P0 workflow.

## Risk Notes

- Local Room VIP state is intentionally not tamper resistant.
- Public quote sources can rate-limit, change format, or become unavailable.
- Research reports are heuristic summaries and should remain framed as records, not advice.
- Community content remains local/demo-only; there is no moderation backend.
