# QuantTradingApp Backend Scaffold

This is a minimal local backend scaffold for account, order, payment-callback, and entitlement flows. It is intended to replace the Android-only local VIP state during the next commercialization phase.

## Run Locally

```bash
cd backend
python3 -m pip install -r requirements.txt
uvicorn app.main:app --reload --port 8080
```

Android emulator debug builds can point at this local server with:

```bash
cd ../QuantTradingApp
./gradlew :app:assembleDebug \
  -PquanttradingBackendSyncEnabled=true \
  -PquanttradingApiBaseUrl=http://10.0.2.2:8080/
```

## Test

```bash
cd backend
python3 -m pytest
```

From the repository root:

```bash
scripts/verify_backend.sh
```

## Deploy Template

The scaffold includes a production-shaped container and Render Blueprint:

- `backend/Dockerfile`
- `backend/.env.example`
- `../render.yaml`

Minimum deploy environment:

```bash
QUANTTRADING_DB_PATH=/data/quanttrading.db
QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE=1
QUANTTRADING_PAYMENT_CALLBACK_SECRET=<strong-secret>
QUANTTRADING_ADMIN_TOKEN=<strong-admin-token>
```

For QA Android builds that must not fall back to local VIP activation:

```bash
QUANTTRADING_API_BASE_URL=https://<deployed-backend>/ ../scripts/build_qa_backend_debug.sh
```

## Implemented

- `POST /v1/auth/register`
- `POST /v1/auth/login`
- `POST /v1/auth/refresh`
- `GET /v1/me/entitlements`
- `DELETE /v1/me`
- `POST /v1/orders`
- `GET /v1/orders/{orderId}`
- `GET /v1/me/orders`
- `POST /v1/payment/callbacks/{channel}`
- `GET /v1/market/capital-flow`
- `GET /v1/market/dragon-list`
- `GET /v1/market/fundamentals`
- `GET /v1/admin/audit`
- `GET /admin`

## Commercial Hardening Included

- Phone/password/device input validation.
- PBKDF2 password hashing with per-user salt.
- Access/refresh token records scoped by device.
- Callback amount/channel checks.
- Optional HMAC callback signature via `QUANTTRADING_PAYMENT_CALLBACK_SECRET`.
- Idempotent paid/refund/cancel callbacks.
- Duplicate provider transaction rejection.
- Payment callback audit table.
- User order list endpoint for Android subscription status history.
- Token-protected read-only admin audit snapshot for users, orders, callbacks, and entitlements.
- Account deletion endpoint for app-store compliance planning.
- VIP-gated premium data proxy endpoints that return an explicit `not_configured` contract until a licensed provider is connected.

## Admin Audit

The admin audit surface is disabled unless `QUANTTRADING_ADMIN_TOKEN` is set.

```bash
curl -H "X-Admin-Token: $QUANTTRADING_ADMIN_TOKEN" http://localhost:8080/v1/admin/audit
open "http://localhost:8080/admin?token=$QUANTTRADING_ADMIN_TOKEN"
```

Both routes are read-only. The JSON route is intended for smoke checks and monitoring scripts; the HTML route is the smallest manual review page for recent users, orders, payment callbacks, and entitlement state.

## Not Production Ready

- Payment callback is still sandbox-only and does not verify native WeChat/Alipay signatures yet.
- Tokens are opaque local records, not a hardened identity platform.
- The admin audit page is read-only and minimal; it is not a full operations console.
- No monitoring, invoice flow, production refund policy, or licensed data-provider credentials are included yet.

See `../docs/SERVER_CONTRACT.md` and `../docs/PAYMENT_INTEGRATION.md` for the production contract and launch gates.
