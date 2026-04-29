# Market Data Provider Strategy

## Current Sources

The app currently uses public/free endpoints for MVP validation:

- Tencent public quote: stock quote and index quote primary source.
- Sina public quote: stock/index quote fallback.
- Tencent K-line: moving-average source.
- EastMoney K-line: moving-average fallback.
- Local Room quote cache: last successful quote snapshot fallback.

These are acceptable for internal testing and product discovery, not for premium commercial data claims.

## Product Claims Allowed Today

- Current sample-pool quote observation.
- Watchlist research and local review records.
- Heuristic diagnosis based on available public fields.
- Local historical snapshots.

## Product Claims Not Allowed Yet

- Full-market coverage.
- Main capital flow or northbound capital flow.
- Official 龙虎榜 data.
- Real-time financial fundamentals.
- Valuation percentiles.
- Data completeness suitable for investment decisions.

## Licensed Provider Requirements

Before paid launch, choose a licensed provider that can supply:

- Quote and historical K-line with SLA and redistribution rights.
- Sector/industry classification.
- Capital flow fields.
- 龙虎榜 and exchange disclosures.
- Financial indicators and valuation distribution.
- API terms allowing mobile app use and paid subscription features.

## Backend Proxy Rules

Premium provider credentials must not be embedded in Android builds.

The backend should:

- Sign provider requests.
- Normalize provider response shape.
- Attach `source`, `sourceUpdatedAt`, and `disclaimer`.
- Cache where license allows.
- Rate-limit by user, device, and endpoint.
- Log provider failures for diagnosis.

## Android UX Rules

- Always show source and stale/cache state.
- Do not fill unavailable premium fields with fake data.
- Keep warnings visible when falling back to cache or sample-pool data.
- Keep all research reports framed as observation, not trading advice.

## Integration Priority

1. Licensed fundamentals and valuation fields for stock diagnosis.
2. Official sector/industry and full-market sample coverage.
3. Capital flow endpoint.
4. 龙虎榜 endpoint.
5. Server-side screeners for paid watchlists and daily sample pools.
