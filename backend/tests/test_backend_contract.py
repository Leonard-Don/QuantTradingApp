import hashlib
import hmac
import time

from fastapi.testclient import TestClient

from app.main import GRACE_MILLIS, create_app


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def register(client: TestClient) -> dict:
    response = client.post(
        "/v1/auth/register",
        json={
            "displayName": "测试用户",
            "phone": "13800000000",
            "password": "passw0rd",
            "deviceId": "device-1",
        },
    )
    assert response.status_code == 200
    return response.json()


def create_order(client: TestClient, token: str, client_order_id: str, tier: str = "STOCK") -> dict:
    response = client.post(
        "/v1/orders",
        headers=auth_headers(token),
        json={
            "tier": tier,
            "durationDays": 31 if tier == "STOCK" else 93,
            "channel": "WECHAT",
            "clientOrderId": client_order_id,
            "deviceId": "device-1",
        },
    )
    assert response.status_code == 200
    return response.json()


def test_register_login_refresh_and_initial_entitlements(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))

    auth = register(client)
    assert auth["userId"].startswith("usr_")
    assert auth["accessToken"].startswith("atk_")
    assert auth["refreshToken"].startswith("rtk_")

    entitlement = client.get(
        "/v1/me/entitlements",
        headers=auth_headers(auth["accessToken"]),
    )
    assert entitlement.status_code == 200
    assert entitlement.json()["stockVipExpireTime"] == 0
    assert entitlement.json()["quantVipExpireTime"] == 0

    login = client.post(
        "/v1/auth/login",
        json={"phone": "13800000000", "password": "passw0rd", "deviceId": "device-1"},
    )
    assert login.status_code == 200
    assert login.json()["userId"] == auth["userId"]

    refresh = client.post(
        "/v1/auth/refresh",
        json={"refreshToken": auth["refreshToken"], "deviceId": "device-1"},
    )
    assert refresh.status_code == 200
    assert refresh.json()["accessToken"].startswith("atk_")


def test_register_rejects_invalid_phone(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))

    response = client.post(
        "/v1/auth/register",
        json={
            "displayName": "测试用户",
            "phone": "not-a-phone",
            "password": "passw0rd",
            "deviceId": "device-1",
        },
    )

    assert response.status_code == 422


def test_order_payment_callback_extends_stock_entitlement(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    order_response = client.post(
        "/v1/orders",
        headers=auth_headers(auth["accessToken"]),
        json={
            "tier": "STOCK",
            "durationDays": 31,
            "channel": "WECHAT",
            "clientOrderId": "client-order-1",
            "deviceId": "device-1",
        },
    )
    assert order_response.status_code == 200
    order = order_response.json()
    assert order["status"] == "PENDING"
    assert order["amountCents"] == 6800
    assert order["paymentPayload"]["sandbox"] is True

    paid = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-1",
            "amountCents": 6800,
            "sandboxApproved": True,
        },
    )
    assert paid.status_code == 200
    assert paid.json()["status"] == "PAID"
    assert paid.json()["stockVipExpireTime"] > 0
    assert paid.json()["quantVipExpireTime"] == 0

    status = client.get(
        f"/v1/orders/{order['orderId']}",
        headers=auth_headers(auth["accessToken"]),
    )
    assert status.status_code == 200
    assert status.json()["status"] == "PAID"
    assert status.json()["tier"] == "STOCK"
    assert status.json()["amountCents"] == 6800
    assert status.json()["entitlement"]["stockVipExpireTime"] == paid.json()["stockVipExpireTime"]

    orders = client.get("/v1/me/orders", headers=auth_headers(auth["accessToken"]))
    assert orders.status_code == 200
    assert orders.json()[0]["orderId"] == order["orderId"]
    assert orders.json()[0]["status"] == "PAID"


def test_payment_callback_is_idempotent_but_rejects_duplicate_provider_transaction(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    first_order = create_order(client, auth["accessToken"], "client-order-dup-1")
    first_paid = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": first_order["orderId"],
            "providerTransactionId": "provider-tx-dup",
            "amountCents": first_order["amountCents"],
        },
    )
    assert first_paid.status_code == 200

    first_retry = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": first_order["orderId"],
            "providerTransactionId": "provider-tx-dup",
            "amountCents": first_order["amountCents"],
        },
    )
    assert first_retry.status_code == 200
    assert first_retry.json()["stockVipExpireTime"] == first_paid.json()["stockVipExpireTime"]

    second_order = create_order(client, auth["accessToken"], "client-order-dup-2")
    second_paid = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": second_order["orderId"],
            "providerTransactionId": "provider-tx-dup",
            "amountCents": second_order["amountCents"],
        },
    )
    assert second_paid.status_code == 409
    assert second_paid.json()["detail"] == "duplicate provider transaction id"


def test_refund_reduces_entitlement_and_keeps_audit_state(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-refund")

    paid = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-refund-pay",
            "amountCents": order["amountCents"],
        },
    )
    assert paid.status_code == 200
    assert paid.json()["stockVipExpireTime"] > 0

    refunded = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-refund",
            "amountCents": order["amountCents"],
            "eventType": "REFUNDED",
        },
    )
    assert refunded.status_code == 200
    assert refunded.json()["status"] == "REFUNDED"
    assert refunded.json()["stockVipExpireTime"] == 0

    status = client.get(
        f"/v1/orders/{order['orderId']}",
        headers=auth_headers(auth["accessToken"]),
    )
    assert status.status_code == 200
    assert status.json()["status"] == "REFUNDED"
    assert status.json()["paidAt"] is not None


def test_order_list_tracks_pending_paid_and_cancelled_statuses(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    pending_order = create_order(client, auth["accessToken"], "client-order-list-pending")
    paid_order = create_order(client, auth["accessToken"], "client-order-list-paid", tier="QUANT")

    paid = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": paid_order["orderId"],
            "providerTransactionId": "provider-tx-list-paid",
            "amountCents": paid_order["amountCents"],
        },
    )
    assert paid.status_code == 200

    cancelled = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": pending_order["orderId"],
            "providerTransactionId": "provider-tx-list-cancelled",
            "amountCents": pending_order["amountCents"],
            "eventType": "CANCELLED",
        },
    )
    assert cancelled.status_code == 200

    orders = client.get("/v1/me/orders?limit=10", headers=auth_headers(auth["accessToken"]))
    assert orders.status_code == 200
    by_id = {order["orderId"]: order for order in orders.json()}
    assert by_id[pending_order["orderId"]]["status"] == "CANCELLED"
    assert by_id[paid_order["orderId"]]["status"] == "PAID"
    assert by_id[paid_order["orderId"]]["tier"] == "QUANT"
    assert by_id[paid_order["orderId"]]["entitlement"]["quantVipExpireTime"] > 0


def test_admin_audit_is_disabled_without_configured_token(tmp_path, monkeypatch):
    monkeypatch.delenv("TIANXIAN_ADMIN_TOKEN", raising=False)
    client = TestClient(create_app(str(tmp_path / "test.db")))

    response = client.get("/v1/admin/audit", headers={"X-Admin-Token": "admin-secret"})

    assert response.status_code == 403
    assert response.json()["detail"] == "admin disabled"


def test_admin_audit_rejects_invalid_token(tmp_path, monkeypatch):
    monkeypatch.setenv("TIANXIAN_ADMIN_TOKEN", "admin-secret")
    client = TestClient(create_app(str(tmp_path / "test.db")))

    response = client.get("/v1/admin/audit", headers={"X-Admin-Token": "wrong-token"})

    assert response.status_code == 401
    assert response.json()["detail"] == "invalid admin token"


def test_admin_audit_returns_read_only_snapshot_and_html_page(tmp_path, monkeypatch):
    monkeypatch.setenv("TIANXIAN_ADMIN_TOKEN", "admin-secret")
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-admin")

    paid = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-admin",
            "amountCents": order["amountCents"],
        },
    )
    assert paid.status_code == 200

    snapshot = client.get("/v1/admin/audit?limit=10", headers={"X-Admin-Token": "admin-secret"})
    assert snapshot.status_code == 200
    data = snapshot.json()
    assert data["counts"]["users"] == 1
    assert data["counts"]["orders"] == 1
    assert data["counts"]["paymentCallbacks"] == 1
    assert data["counts"]["activeEntitlements"] == 1
    assert data["orderStatusCounts"]["PAID"] == 1
    assert data["recentOrders"][0]["order_id"] == order["orderId"]
    assert data["recentPaymentCallbacks"][0]["provider_transaction_id"] == "provider-tx-admin"

    html = client.get("/admin?token=admin-secret")
    assert html.status_code == 200
    assert "TianXianQuant Admin Audit" in html.text
    assert order["orderId"] in html.text
    assert "provider-tx-admin" in html.text
    assert "13800000000" in html.text


def test_market_proxy_requires_vip_and_returns_not_configured_contract(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    locked = client.get(
        "/v1/market/fundamentals?codes=600519",
        headers=auth_headers(auth["accessToken"]),
    )
    assert locked.status_code == 403

    order = create_order(client, auth["accessToken"], "client-order-market")
    paid = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-market",
            "amountCents": order["amountCents"],
        },
    )
    assert paid.status_code == 200

    proxy = client.get(
        "/v1/market/fundamentals?codes=600519",
        headers=auth_headers(auth["accessToken"]),
    )
    assert proxy.status_code == 200
    assert proxy.json()["status"] == "not_configured"
    assert proxy.json()["data"] == []
    assert "不构成投资建议" in proxy.json()["disclaimer"]


def test_payment_callback_signature_can_be_required(tmp_path, monkeypatch):
    monkeypatch.setenv("TIANXIAN_PAYMENT_CALLBACK_SECRET", "secret")
    monkeypatch.setenv("TIANXIAN_REQUIRE_CALLBACK_SIGNATURE", "1")
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-signature")

    unsigned = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-signature",
            "amountCents": order["amountCents"],
        },
    )
    assert unsigned.status_code == 401

    timestamp = int(time.time() * 1000)
    message = (
        f"{timestamp}:"
        f"{order['orderId']}:provider-tx-signature:{order['amountCents']}:PAID"
    )
    signature = hmac.new(b"secret", message.encode("utf-8"), hashlib.sha256).hexdigest()
    signed = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-signature",
            "amountCents": order["amountCents"],
            "timestamp": timestamp,
            "signature": signature,
        },
    )
    assert signed.status_code == 200
    assert signed.json()["status"] == "PAID"


def test_payment_callback_rejects_missing_timestamp_when_signed(tmp_path, monkeypatch):
    monkeypatch.setenv("TIANXIAN_PAYMENT_CALLBACK_SECRET", "secret")
    monkeypatch.setenv("TIANXIAN_REQUIRE_CALLBACK_SIGNATURE", "1")
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-no-ts")

    message = f"{order['orderId']}:provider-tx-no-ts:{order['amountCents']}:PAID"
    signature = hmac.new(b"secret", message.encode("utf-8"), hashlib.sha256).hexdigest()
    response = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-no-ts",
            "amountCents": order["amountCents"],
            "signature": signature,
        },
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "payment callback timestamp is required"


def test_payment_callback_rejects_replayed_old_timestamp(tmp_path, monkeypatch):
    monkeypatch.setenv("TIANXIAN_PAYMENT_CALLBACK_SECRET", "secret")
    monkeypatch.setenv("TIANXIAN_REQUIRE_CALLBACK_SIGNATURE", "1")
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-replay")

    stale_timestamp = int(time.time() * 1000) - 10 * 60 * 1000
    message = (
        f"{stale_timestamp}:"
        f"{order['orderId']}:provider-tx-replay:{order['amountCents']}:PAID"
    )
    signature = hmac.new(b"secret", message.encode("utf-8"), hashlib.sha256).hexdigest()
    response = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-replay",
            "amountCents": order["amountCents"],
            "timestamp": stale_timestamp,
            "signature": signature,
        },
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "payment callback timestamp outside allowed window"


def test_payment_callback_failure_is_recorded_in_audit(tmp_path, monkeypatch):
    monkeypatch.setenv("TIANXIAN_ADMIN_TOKEN", "admin-secret")
    monkeypatch.setenv("TIANXIAN_PAYMENT_CALLBACK_SECRET", "secret")
    monkeypatch.setenv("TIANXIAN_REQUIRE_CALLBACK_SIGNATURE", "1")
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-audit")

    bad = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-audit",
            "amountCents": order["amountCents"],
            "timestamp": int(time.time() * 1000),
            "signature": "deadbeef",
        },
    )
    assert bad.status_code == 401

    snapshot = client.get(
        "/v1/admin/audit?limit=10",
        headers={"X-Admin-Token": "admin-secret"},
    )
    assert snapshot.status_code == 200
    callbacks = snapshot.json()["recentPaymentCallbacks"]
    assert callbacks
    assert callbacks[0]["accepted"] == 0
    assert callbacks[0]["detail"].startswith("signature:")


def test_delete_account_removes_user_and_invalidates_token(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    deleted = client.delete("/v1/me", headers=auth_headers(auth["accessToken"]))
    assert deleted.status_code == 200
    assert deleted.json()["status"] == "deleted"

    entitlement = client.get(
        "/v1/me/entitlements",
        headers=auth_headers(auth["accessToken"]),
    )
    assert entitlement.status_code == 401


def test_health_reports_db_status(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))

    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["db"] == "ok"
    assert isinstance(body["dbLatencyMs"], int)
    assert isinstance(body["serverTime"], int)


def test_rate_limit_returns_429_after_burst(tmp_path):
    client = TestClient(
        create_app(
            str(tmp_path / "test.db"),
            rate_limit_rules={"/v1/auth/login": (3, 60)},
        )
    )

    payload = {"phone": "13800000000", "password": "passw0rd", "deviceId": "device-1"}
    statuses = []
    for _ in range(5):
        statuses.append(client.post("/v1/auth/login", json=payload).status_code)

    assert statuses[-1] == 429
    assert statuses.count(429) >= 1


def test_grace_until_is_zero_for_never_paid_user_and_extends_after_payment(tmp_path):
    # backend/app/main.py:418 computes
    #   graceUntil = latest_expiry + GRACE_MILLIS if latest_expiry > 0 else 0
    # The `else 0` branch is load-bearing: a naive simplification to
    # `latest_expiry + GRACE_MILLIS` or `(latest_expiry or 0) + GRACE_MILLIS`
    # would silently give never-paid users a 7-day grace window. The Android
    # client honors graceUntil as a valid cached-entitlement fallback during
    # offline use, so a non-zero grace for a never-paid user would let them
    # bypass the paywall until the cache expired. Existing initial-entitlement
    # tests only assert the two expiry fields; this locks the explicit-zero
    # invariant for graceUntil and the +GRACE_MILLIS extension after payment.
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    initial = client.get(
        "/v1/me/entitlements",
        headers=auth_headers(auth["accessToken"]),
    )
    assert initial.status_code == 200
    initial_body = initial.json()
    assert initial_body["stockVipExpireTime"] == 0
    assert initial_body["quantVipExpireTime"] == 0
    assert initial_body["graceUntil"] == 0, (
        "graceUntil must be explicit 0 for a never-paid user, not GRACE_MILLIS; "
        f"got {initial_body['graceUntil']}"
    )

    order = create_order(client, auth["accessToken"], "client-order-grace")
    paid = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-grace",
            "amountCents": order["amountCents"],
        },
    )
    assert paid.status_code == 200

    after = client.get(
        "/v1/me/entitlements",
        headers=auth_headers(auth["accessToken"]),
    )
    assert after.status_code == 200
    after_body = after.json()
    expected_latest = max(
        after_body["stockVipExpireTime"], after_body["quantVipExpireTime"]
    )
    assert expected_latest > 0
    assert after_body["graceUntil"] == expected_latest + GRACE_MILLIS, (
        "graceUntil after payment must equal max(stock, quant) + GRACE_MILLIS; "
        f"got grace={after_body['graceUntil']}, latest={expected_latest}"
    )


def test_callback_rejects_amount_mismatch(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = client.post(
        "/v1/orders",
        headers=auth_headers(auth["accessToken"]),
        json={
            "tier": "QUANT",
            "durationDays": 93,
            "channel": "ALIPAY",
            "clientOrderId": "client-order-2",
            "deviceId": "device-1",
        },
    ).json()

    paid = client.post(
        "/v1/payment/callbacks/ALIPAY",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-2",
            "amountCents": 1,
            "sandboxApproved": True,
        },
    )
    assert paid.status_code == 400
    assert paid.json()["detail"] == "payment amount mismatch"
