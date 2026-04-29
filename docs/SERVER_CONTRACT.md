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
  "status": "PAID",
  "paidAt": 1770000000000,
  "entitlement": {
    "stockVipExpireTime": 1771000000000,
    "quantVipExpireTime": 0
  }
}
```

## Payment Callbacks

### POST `/v1/payment/callbacks/{channel}`

Handled by the backend only. The app must not call this endpoint.

Backend requirements:

- Verify merchant signature.
- Ensure idempotency by provider transaction ID.
- Match amount, currency, channel, and order ID.
- Activate entitlement only after verified payment.
- Persist audit events for paid, refunded, chargeback, and cancelled states.

## Data Provider Proxy

Premium data should be requested through the backend so provider credentials are not shipped in the APK.

Examples:

- GET `/v1/market/capital-flow?codes=600519,300750`
- GET `/v1/market/dragon-list?date=2026-04-29`
- GET `/v1/market/fundamentals?codes=600519,300750`

Each response must include:

```json
{
  "source": "licensed-provider-name",
  "sourceUpdatedAt": 1770000000000,
  "data": [],
  "disclaimer": "研究参考，不构成投资建议"
}
```
