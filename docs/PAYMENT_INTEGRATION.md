# Payment Integration Plan

## Current App Boundary

`PaymentGateway` is intentionally a local boundary:

- Debug builds can return `DebugPaid` for local verification.
- Debug builds can optionally create a backend sandbox order when `tianxianBackendSyncEnabled=true`.
- Release builds return `NotConfigured` and must not activate VIP.

This keeps the Android UI flow testable without pretending real money collection exists.

## Required Production Flow

1. App requests backend order creation.
2. Backend creates provider order with WeChat Pay or Alipay.
3. Backend returns provider payment payload to the app.
4. App launches the provider SDK.
5. Provider notifies backend callback URL.
6. Backend verifies signature, amount, channel, order ID, and idempotency key.
7. Backend activates entitlement.
8. App polls order status or refreshes `/v1/me/entitlements`.

## Android Tasks

- Add WeChat Pay SDK and Alipay SDK only after merchant accounts are available.
- Keep the build-flagged backend order path and replace the Debug sandbox callback with merchant SDK confirmation once merchant accounts are available.
- Store order IDs, status, amount, channel, entitlement snapshots, and source locally. `[scaffolded]`
- Use `-PtianxianRequireBackendPaymentSync=true` for QA builds that must block local VIP fallback when backend order/callback/entitlement sync fails. `[scaffolded]`
- Use `scripts/verify_paid_release_config.sh` as the paid/store release guard for production API, legal URLs, support email, backend sync, and signing input. `[scaffolded]`
- Add UI states for pending, paid, failed, cancelled, refunded, and manual review. `[partially scaffolded: pending/paid/refunded/cancelled/manual-review labels are visible from cached order history]`

## Backend Tasks

- Order table with immutable amount, tier, duration, channel, and status. `[scaffolded]`
- Payment callback table with provider transaction ID and audit detail. `[scaffolded]`
- Idempotent entitlement activation. `[scaffolded]`
- Refund/cancel event handling. `[scaffolded]`
- User order list endpoint for Android status refresh. `[scaffolded]`
- Admin audit view for order, callback, and entitlement changes. `[scaffolded: read-only JSON and HTML, gated by TIANXIAN_ADMIN_TOKEN]`

## Test Matrix

| Case | Expected |
| --- | --- |
| Successful payment | Order paid, entitlement extended from current active expiry |
| Expired user renews | Entitlement extends from server current time |
| Duplicate callback | No double extension |
| Amount mismatch | Callback rejected and order marked manual review |
| Signature failure | Callback rejected |
| Refund | Entitlement reduced or marked revoked according to policy |
| Offline after payment | App keeps pending state until entitlement refresh succeeds |

Current scaffold coverage:

- Duplicate `PAID` callback for the same order is idempotent.
- Duplicate provider transaction ID on another order is rejected.
- Amount and channel mismatch are rejected.
- Optional HMAC callback signature can be required with `TIANXIAN_REQUIRE_CALLBACK_SIGNATURE=1`.
- `REFUNDED` subtracts the paid duration from the entitlement snapshot; if the resulting time is already expired, it revokes to `0`.
- `CANCELLED` can close a pending order without activating entitlement.

## Release Gate

Paid release is blocked until:

- Backend order API is live.
- Provider callbacks are verified in sandbox.
- Entitlement API is integrated.
- App release build cannot trigger local VIP activation.
- Android stores local subscription-order snapshots and shows recent order status on the VIP page.
- Backend exposes `GET /v1/me/orders` for user order history and status refresh.
- Backend exposes token-protected admin audit routes for order/callback/entitlement reconciliation.
- QA builds require backend payment sync before release candidate sign-off.
- `verifyPaidReleaseConfig` passes with production API, legal URLs, support email, data disclaimer, and signing input.
- Privacy policy and subscription rules mention renewal/refund behavior.
- Android app backend sync is enabled against the deployed API, with Room used only as a cache.
- Production merchant native signatures replace the scaffold HMAC.
