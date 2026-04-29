# TianXianQuant Backend Scaffold

This is a minimal local backend scaffold for account, order, payment-callback, and entitlement flows. It is intended to replace the Android-only local VIP state during the next commercialization phase.

## Run Locally

```bash
cd backend
python3 -m pip install -r requirements.txt
uvicorn app.main:app --reload --port 8080
```

## Test

```bash
cd backend
python3 -m pytest
```

## Implemented

- `POST /v1/auth/register`
- `POST /v1/auth/login`
- `POST /v1/auth/refresh`
- `GET /v1/me/entitlements`
- `POST /v1/orders`
- `GET /v1/orders/{orderId}`
- `POST /v1/payment/callbacks/{channel}`

## Not Production Ready

- Payment callback is sandbox-only and does not verify WeChat/Alipay signatures yet.
- Password hashing is a scaffold and should be replaced with a stronger password hashing library before production.
- Tokens are opaque local records, not a hardened identity platform.
- No admin console, monitoring, refund workflow, or licensed data-provider proxy is included yet.

See `../docs/SERVER_CONTRACT.md` and `../docs/PAYMENT_INTEGRATION.md` for the production contract and launch gates.
