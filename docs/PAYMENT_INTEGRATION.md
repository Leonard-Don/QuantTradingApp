# Payment Integration Plan

## Current App Boundary

`PaymentGateway` is intentionally a local boundary:

- Debug builds can return `DebugPaid` for local verification.
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
- Replace `PaymentGateway.startSubscription()` implementation with backend order creation.
- Store only order IDs and entitlement snapshots locally.
- Add release guard that fails fast if payment configuration is missing.
- Add UI states for pending, paid, failed, cancelled, refunded, and manual review.

## Backend Tasks

- Order table with immutable amount, tier, duration, channel, and status. `[scaffolded]`
- Payment callback table with provider transaction ID and audit detail. `[scaffolded]`
- Idempotent entitlement activation. `[scaffolded]`
- Refund/cancel event handling. `[scaffolded]`
- Admin audit view for order and entitlement changes.

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
- Privacy policy and subscription rules mention renewal/refund behavior.
- Android app calls backend order/entitlement endpoints instead of local Room as source of truth.
- Production merchant native signatures replace the scaffold HMAC.
