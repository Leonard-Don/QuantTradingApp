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
| Real payments | Not connected to merchant/server callbacks | No-go for paid launch |
| Server-side entitlement | Not implemented | No-go for paid launch |
| Market data authorization | Public/free sources only | No-go for premium data claims |
| Privacy/legal artifacts | Drafts only | No-go for store submission |
| Release signing/AAB | Not configured | No-go for store submission |

## Must Finish Before Paid Launch

1. Server account system with phone/password or OAuth, session refresh, and device binding.
2. Server-side VIP entitlement source of truth, with app-side cached entitlement and grace period.
3. Payment order API, merchant SDK integration, callback verification, refund/cancel handling, and audit logs.
4. Licensed market-data provider contract for premium claims such as main capital flow, full-market coverage,龙虎榜, financial indicators, and valuation percentiles.
5. Privacy policy, user agreement, paid subscription rules, data-source disclaimer, and app-store metadata.
6. Release signing, AAB build, versioning policy, and rollback plan.

## Can Ship As Internal Demo

- Multi-source public quote/K-line fallback and local quote cache.
- Local watchlist, stock diagnosis, watchlist health, review history, quant diagnosis, community digest.
- Debug-only local payment simulation.
- Repeatable local verification scripts and GitHub Actions P0 workflow.

## Risk Notes

- Local Room VIP state is intentionally not tamper resistant.
- Public quote sources can rate-limit, change format, or become unavailable.
- Research reports are heuristic summaries and should remain framed as records, not advice.
- Community content remains local/demo-only; there is no moderation backend.
