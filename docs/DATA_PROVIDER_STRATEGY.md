# Market Data Provider Strategy

## Current Sources

The app uses public endpoints and local cache for an Android research demo:

- Tencent public quote endpoint for stock and index quote samples.
- Sina public quote endpoint as a quote fallback.
- Tencent K-line endpoint for moving-average samples.
- EastMoney K-line endpoint as a K-line fallback.
- Local Room quote cache for the last successful snapshot.

These sources are suitable for local demos, UI validation, and research-note workflows. They are not suitable for full-market coverage claims, real-time trading decisions, or redistribution.

## Allowed Product Claims

- Current sample-pool quote observation.
- Watchlist research and local review records.
- Heuristic diagnosis based on available public fields.
- Historical sample simulation for research only.
- Local reminders, holdings notes, posts, comments, and strategy drafts.

## Claims Not Allowed

- Full-market coverage.
- Guaranteed quote timeliness or completeness.
- Official capital-flow coverage.
- Complete 龙虎榜 seat-level details.
- Real-time financial fundamentals.
- Valuation percentiles suitable for investment decisions.
- Any trading advice, broker execution, order placement, or return promise.

## Android UX Rules

- Always show source and stale/cache state where available.
- Do not fill unavailable fields with invented data.
- Keep warnings visible when falling back to cache or sample-pool data.
- Keep all research reports framed as observation, not trading advice.
- Keep user-created data local unless a future contributor intentionally designs a new sync system.

## Future Provider Work

If the project ever adds additional data providers, credentials must stay outside Android builds, provider terms must allow the intended use, and documentation must clearly describe the source, delay, coverage, and redistribution limits.
