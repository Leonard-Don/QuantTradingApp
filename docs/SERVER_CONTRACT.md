# TianXianQuant Minimal Server Contract

This document defines the smallest backend needed to replace the current local-only account and VIP state.

## Principles

- The server is the source of truth for accounts, orders, and VIP entitlements.
- The app may cache entitlements for offline UX, but cache must expire.
- The app must never activate paid VIP from a client-only payment result in release builds.
- All timestamps use Unix milliseconds in UTC.

## Auth

### POST `/v1/auth/register`

Request:

```json
{
  "displayName": "本机用户",
  "phone": "13800000000",
  "password": "user password",
  "deviceId": "android-id-or-install-id"
}
```

Response:

```json
{
  "userId": "usr_123",
  "accessToken": "jwt",
  "refreshToken": "opaque-refresh-token",
  "expiresAt": 1770000000000
}
```

### POST `/v1/auth/login`

Same request and response shape as register, without `displayName`.

### POST `/v1/auth/refresh`

Request:

```json
{
  "refreshToken": "opaque-refresh-token",
  "deviceId": "android-id-or-install-id"
}
```

Response:

```json
{
  "accessToken": "jwt",
  "refreshToken": "opaque-refresh-token",
  "expiresAt": 1770000000000
}
```

## Entitlements

### GET `/v1/me/entitlements`

Response:

```json
{
  "userId": "usr_123",
  "serverTime": 1770000000000,
  "stockVipExpireTime": 1771000000000,
  "quantVipExpireTime": 1771000000000,
  "graceUntil": 1771600000000,
  "source": "payment_order"
}
```

App behavior:

- Treat VIP as active only if `serverTime < expireTime` or `serverTime < graceUntil`.
- Persist the latest verified entitlement with `lastVerifiedAt`.
- If offline, allow cached entitlement only inside the grace window.
- Android debug builds can enable this contract with `-PtianxianBackendSyncEnabled=true -PtianxianApiBaseUrl=http://10.0.2.2:8080/`.
- The Android account page uses the enabled contract for login, access-token refresh, entitlement refresh, debug sandbox subscription sync, and account deletion.

### DELETE `/v1/me`

Deletes the account and server-side rows for sessions, entitlements, orders, and callback audit records.
After a successful Android delete call, the app also clears local user state, watchlist, holdings, posts, comments, strategies, review snapshots, and quote cache.

Response:

```json
{
  "status": "deleted"
}
```

## Orders

### POST `/v1/orders`

Request:

```json
{
  "tier": "STOCK",
  "durationDays": 31,
  "channel": "WECHAT",
  "clientOrderId": "uuid",
  "deviceId": "android-id-or-install-id"
}
```

Response:

```json
{
  "orderId": "ord_123",
  "tier": "STOCK",
  "durationDays": 31,
  "amountCents": 6800,
  "currency": "CNY",
  "channel": "WECHAT",
  "status": "PENDING",
  "paymentPayload": {
    "appId": "merchant-app-id",
    "partnerId": "merchant-id",
    "prepayId": "prepay-id",
    "nonceStr": "nonce",
    "timestamp": "1770000000",
    "sign": "signature"
  }
}
```

### GET `/v1/orders/{orderId}`

Response:

```json
{
  "orderId": "ord_123",
  "tier": "STOCK",
  "durationDays": 31,
  "amountCents": 6800,
  "currency": "CNY",
  "channel": "WECHAT",
  "status": "PAID",
  "createdAt": 1770000000000,
  "paidAt": 1770000000000,
  "entitlement": {
    "stockVipExpireTime": 1771000000000,
    "quantVipExpireTime": 0
  }
}
```

### GET `/v1/me/orders`

Returns the current user's recent subscription orders, newest first, using the same response shape as `/v1/orders/{orderId}`. Android caches this list locally so users can see pending, paid, refunded, and cancelled subscription state even after reconnecting or reopening the app.

## Payment Callbacks

### POST `/v1/payment/callbacks/{channel}`

Handled by the backend only in production. The Android debug build may call this endpoint only when backend sync and local payment simulation are both enabled, so QA can verify the order-entitlement loop without merchant credentials.

Sandbox request shape:

```json
{
  "orderId": "ord_123",
  "providerTransactionId": "provider_tx_123",
  "amountCents": 6800,
  "eventType": "PAID",
  "sandboxApproved": true,
  "signature": "optional-hmac"
}
```

Backend requirements:

- Verify merchant signature.
- Reject channel or amount mismatch.
- Ensure idempotency by provider transaction ID.
- Match amount, currency, channel, and order ID.
- Activate entitlement only after verified payment.
- Persist callback audit events.
- Persist audit events for paid, refunded, chargeback, and cancelled states.

Current local backend scaffold supports sandbox `PAID`, `REFUNDED`, and `CANCELLED` events, optional HMAC via `TIANXIAN_PAYMENT_CALLBACK_SECRET`, duplicate provider transaction rejection, and callback audit rows. Production WeChat/Alipay native signature verification is still a merchant-credential task.

## Admin Audit

### GET `/v1/admin/audit`

Returns a read-only operational snapshot for smoke checks and manual reconciliation. The route is disabled unless `TIANXIAN_ADMIN_TOKEN` is configured. Clients must pass either `X-Admin-Token` or `?token=`.

Response:

```json
{
  "generatedAt": 1770000000000,
  "counts": {
    "users": 1,
    "orders": 1,
    "paymentCallbacks": 1,
    "activeEntitlements": 1
  },
  "orderStatusCounts": {
    "PAID": 1
  },
  "recentUsers": [],
  "recentOrders": [],
  "recentPaymentCallbacks": [],
  "entitlements": []
}
```

### GET `/admin`

Returns the same read-only snapshot as a minimal HTML page for manual backend review. It must not expose mutation actions.

## Data Provider Proxy

Premium data should be requested through the backend so provider credentials are not shipped in the APK.

Examples:

- GET `/v1/market/capital-flow?codes=600519,300750`
- GET `/v1/market/dragon-list?date=2026-04-29`
- GET `/v1/market/fundamentals?codes=600519,300750`

These endpoints require an active VIP entitlement. Until a licensed provider is configured, the local scaffold returns `status = "not_configured"` with an empty `data` array instead of generating fake premium data.

Each response must include:

```json
{
  "source": "licensed-provider-name",
  "sourceUpdatedAt": 1770000000000,
  "data": [],
  "disclaimer": "研究参考，不构成投资建议"
}
```
