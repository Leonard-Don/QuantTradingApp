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


CALLBACK_SECRET = "test-callback-secret"


def signed_callback_body(order: dict, provider_tx: str, event_type: str = "PAID") -> dict:
    amount = order["amountCents"]
    timestamp = int(time.time() * 1000)
    message = f"{timestamp}:{order['orderId']}:{provider_tx}:{amount}:{event_type}"
    signature = hmac.new(CALLBACK_SECRET.encode(), message.encode(), hashlib.sha256).hexdigest()
    return {
        "orderId": order["orderId"],
        "providerTransactionId": provider_tx,
        "amountCents": amount,
        "eventType": event_type,
        "timestamp": timestamp,
        "signature": signature,
    }


def post_callback(
    client: TestClient,
    order: dict,
    provider_tx: str,
    channel: str = "WECHAT",
    event_type: str = "PAID",
):
    return client.post(
        f"/v1/payment/callbacks/{channel}",
        json=signed_callback_body(order, provider_tx, event_type),
    )


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


def test_order_payment_callback_extends_stock_entitlement(tmp_path, monkeypatch):
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", CALLBACK_SECRET)
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

    paid = post_callback(client, order, "provider-tx-1")
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


def test_payment_callback_is_idempotent_but_rejects_duplicate_provider_transaction(tmp_path, monkeypatch):
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", CALLBACK_SECRET)
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    first_order = create_order(client, auth["accessToken"], "client-order-dup-1")
    first_paid = post_callback(client, first_order, "provider-tx-dup")
    assert first_paid.status_code == 200

    first_retry = post_callback(client, first_order, "provider-tx-dup")
    assert first_retry.status_code == 200
    assert first_retry.json()["stockVipExpireTime"] == first_paid.json()["stockVipExpireTime"]

    second_order = create_order(client, auth["accessToken"], "client-order-dup-2")
    second_paid = post_callback(client, second_order, "provider-tx-dup")
    assert second_paid.status_code == 409
    assert second_paid.json()["detail"] == "duplicate provider transaction id"


def test_refund_reduces_entitlement_and_keeps_audit_state(tmp_path, monkeypatch):
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", CALLBACK_SECRET)
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-refund")

    paid = post_callback(client, order, "provider-tx-refund-pay")
    assert paid.status_code == 200
    assert paid.json()["stockVipExpireTime"] > 0

    refunded = post_callback(client, order, "provider-tx-refund", event_type="REFUNDED")
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


def test_order_list_tracks_pending_paid_and_cancelled_statuses(tmp_path, monkeypatch):
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", CALLBACK_SECRET)
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    pending_order = create_order(client, auth["accessToken"], "client-order-list-pending")
    paid_order = create_order(client, auth["accessToken"], "client-order-list-paid", tier="QUANT")

    paid = post_callback(client, paid_order, "provider-tx-list-paid")
    assert paid.status_code == 200

    cancelled = post_callback(
        client, pending_order, "provider-tx-list-cancelled", event_type="CANCELLED"
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
    monkeypatch.delenv("QUANTTRADING_ADMIN_TOKEN", raising=False)
    client = TestClient(create_app(str(tmp_path / "test.db")))

    response = client.get("/v1/admin/audit", headers={"X-Admin-Token": "admin-secret"})

    assert response.status_code == 403
    assert response.json()["detail"] == "admin disabled"


def test_admin_audit_rejects_invalid_token(tmp_path, monkeypatch):
    monkeypatch.setenv("QUANTTRADING_ADMIN_TOKEN", "admin-secret")
    client = TestClient(create_app(str(tmp_path / "test.db")))

    response = client.get("/v1/admin/audit", headers={"X-Admin-Token": "wrong-token"})

    assert response.status_code == 401
    assert response.json()["detail"] == "invalid admin token"


def test_admin_audit_returns_read_only_snapshot_and_html_page(tmp_path, monkeypatch):
    monkeypatch.setenv("QUANTTRADING_ADMIN_TOKEN", "admin-secret")
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", CALLBACK_SECRET)
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-admin")

    paid = post_callback(client, order, "provider-tx-admin")
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
    assert "QuantTradingApp Admin Audit" in html.text
    assert order["orderId"] in html.text
    assert "provider-tx-admin" in html.text
    assert "13800000000" in html.text


def test_market_proxy_requires_vip_and_returns_not_configured_contract(tmp_path, monkeypatch):
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", CALLBACK_SECRET)
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    locked = client.get(
        "/v1/market/fundamentals?codes=600519",
        headers=auth_headers(auth["accessToken"]),
    )
    assert locked.status_code == 403

    order = create_order(client, auth["accessToken"], "client-order-market")
    paid = post_callback(client, order, "provider-tx-market")
    assert paid.status_code == 200

    proxy = client.get(
        "/v1/market/fundamentals?codes=600519",
        headers=auth_headers(auth["accessToken"]),
    )
    assert proxy.status_code == 200
    assert proxy.json()["status"] == "not_configured"
    assert proxy.json()["data"] == []
    assert "不构成投资建议" in proxy.json()["disclaimer"]


def test_payment_callback_rejects_unsigned_callback_when_secret_unset(tmp_path, monkeypatch):
    # Security: payment callbacks must fail closed. With no callback secret
    # configured, an unsigned callback must be rejected, not silently accepted
    # - otherwise anyone who knows an orderId can forge a PAID callback and
    # grant themselves VIP for free. The unsigned path is reachable only via an
    # explicit QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE=0 opt-out.
    monkeypatch.delenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", raising=False)
    monkeypatch.delenv("QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE", raising=False)
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-unsigned")

    forged = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-forged",
            "amountCents": order["amountCents"],
        },
    )
    assert forged.status_code == 401, (
        "an unsigned callback must be rejected when no callback secret is "
        f"configured; got status={forged.status_code}, body={forged.text!r}"
    )

    entitlement = client.get(
        "/v1/me/entitlements",
        headers=auth_headers(auth["accessToken"]),
    )
    assert entitlement.status_code == 200
    assert entitlement.json()["stockVipExpireTime"] == 0, (
        "a rejected payment callback must not grant any VIP entitlement"
    )


def test_payment_callback_unsigned_path_allowed_only_with_explicit_opt_out(tmp_path, monkeypatch):
    # The unsigned callback path stays available for local/dev use, but only
    # when an operator explicitly opts in. This guards the escape hatch so a
    # future change neither silently removes local-dev usability nor lets the
    # insecure default creep back.
    monkeypatch.delenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", raising=False)
    monkeypatch.setenv("QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE", "0")
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)
    order = create_order(client, auth["accessToken"], "client-order-optout")

    paid = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-optout",
            "amountCents": order["amountCents"],
        },
    )
    assert paid.status_code == 200
    assert paid.json()["status"] == "PAID"


def test_payment_callback_signature_can_be_required(tmp_path, monkeypatch):
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", "secret")
    monkeypatch.setenv("QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE", "1")
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
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", "secret")
    monkeypatch.setenv("QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE", "1")
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
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", "secret")
    monkeypatch.setenv("QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE", "1")
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
    monkeypatch.setenv("QUANTTRADING_ADMIN_TOKEN", "admin-secret")
    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", "secret")
    monkeypatch.setenv("QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE", "1")
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


def test_grace_until_is_zero_for_never_paid_user_and_extends_after_payment(tmp_path, monkeypatch):
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

    monkeypatch.setenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", CALLBACK_SECRET)
    order = create_order(client, auth["accessToken"], "client-order-grace")
    paid = post_callback(client, order, "provider-tx-grace")
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


def test_create_order_enforces_same_device_id_bounds_as_auth_requests(tmp_path):
    # RegisterRequest, LoginRequest, and RefreshRequest all bound deviceId to
    # min_length=3, max_length=128 (backend/app/main.py:155,161,166). The DB
    # column ``device_id TEXT NOT NULL`` enforces non-null only, so the
    # Pydantic constraint is the sole length gate between the JSON body and
    # the SQLite INSERT. OrderRequest must use the same bounds: a device that
    # was rejected at /v1/auth/register would otherwise still be able to
    # create orders, persisting a deviceId in the orders table whose shape
    # the auth tables would refuse. Lock both ends of the range so a future
    # refactor that drops the constraint - or relaxes only one side - fails
    # loudly instead of silently widening the contract.
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    short = client.post(
        "/v1/orders",
        headers=auth_headers(auth["accessToken"]),
        json={
            "tier": "STOCK",
            "durationDays": 31,
            "channel": "WECHAT",
            "clientOrderId": "client-order-device-short",
            "deviceId": "ab",
        },
    )
    assert short.status_code == 422, (
        "OrderRequest.deviceId must reject a 2-character value the way "
        "RegisterRequest/LoginRequest/RefreshRequest do; "
        f"got status={short.status_code}, body={short.text!r}"
    )

    too_long = client.post(
        "/v1/orders",
        headers=auth_headers(auth["accessToken"]),
        json={
            "tier": "STOCK",
            "durationDays": 31,
            "channel": "WECHAT",
            "clientOrderId": "client-order-device-long",
            "deviceId": "d" * 129,
        },
    )
    assert too_long.status_code == 422, (
        "OrderRequest.deviceId must reject a value above max_length=128 the "
        "way RegisterRequest/LoginRequest/RefreshRequest do; "
        f"got status={too_long.status_code}, body={too_long.text!r}"
    )

    boundary = client.post(
        "/v1/orders",
        headers=auth_headers(auth["accessToken"]),
        json={
            "tier": "STOCK",
            "durationDays": 31,
            "channel": "WECHAT",
            "clientOrderId": "client-order-device-boundary",
            "deviceId": "abc",
        },
    )
    assert boundary.status_code == 200, (
        "OrderRequest.deviceId must still accept a 3-character value at the "
        "min_length boundary; "
        f"got status={boundary.status_code}, body={boundary.text!r}"
    )


def test_concurrent_paid_callbacks_extend_entitlement_only_once(tmp_path, monkeypatch):
    # Two PAID callbacks for the same order arriving concurrently must extend
    # the entitlement exactly once. sandbox_payment_callback reads the order
    # status, then later reads and rewrites the entitlement; without a write
    # lock taken up front, the second callback can pass a stale PENDING status
    # check and then read the entitlement the first callback already extended,
    # doubling the granted VIP window. verify_callback_signature is replaced
    # with a one-sided gate so the damaging interleaving is forced
    # deterministically instead of depending on thread timing.
    import threading

    from app.main import (
        DAY_MILLIS,
        BackendStore,
        OrderRequest,
        PaymentCallbackRequest,
        now_millis,
    )

    b_at_gate = threading.Event()
    b_may_proceed = threading.Event()
    call_lock = threading.Lock()
    calls = {"n": 0}

    def gated_verify(request):
        with call_lock:
            is_first = calls["n"] == 0
            calls["n"] += 1
        if is_first:
            # Thread B: pause between the order-status read and the
            # entitlement read until the other callback has fully committed.
            b_at_gate.set()
            assert b_may_proceed.wait(timeout=5), "concurrency gate never released"

    monkeypatch.setattr("app.main.verify_callback_signature", gated_verify)

    store = BackendStore(str(tmp_path / "test.db"))
    auth = store.create_user("用户", "13800000000", "passw0rd", "device-1")
    order = store.create_order(
        auth.userId,
        OrderRequest(
            tier="STOCK",
            durationDays=31,
            channel="WECHAT",
            clientOrderId="client-order-concurrent",
            deviceId="device-1",
        ),
    )

    results = {}
    errors = {}

    def run(name):
        request = PaymentCallbackRequest(
            orderId=order.orderId,
            providerTransactionId="provider-tx-concurrent",
            amountCents=order.amountCents,
            sandboxApproved=True,
        )
        try:
            results[name] = store.sandbox_payment_callback("WECHAT", request)
        except Exception as exc:  # surfaced via the errors assertion below
            errors[name] = exc

    start = now_millis()
    thread_b = threading.Thread(target=run, args=("B",))
    thread_b.start()
    assert b_at_gate.wait(timeout=5), "thread B never reached the gate"

    thread_a = threading.Thread(target=run, args=("A",))
    thread_a.start()
    # Give thread A time to either finish (no up-front lock) or block on the
    # write lock (lock taken up front), then let thread B continue.
    thread_a.join(timeout=1.0)
    b_may_proceed.set()
    thread_a.join(timeout=5)
    thread_b.join(timeout=5)

    assert not errors, f"concurrent callbacks raised: {errors}"
    assert not thread_a.is_alive() and not thread_b.is_alive(), "callback thread hung"
    assert results["A"].status == "PAID" and results["B"].status == "PAID"

    entitlement = store.entitlements(auth.userId)
    one_grant_ceiling = start + 32 * DAY_MILLIS
    assert start + 31 * DAY_MILLIS <= entitlement.stockVipExpireTime < one_grant_ceiling, (
        "two concurrent PAID callbacks for one order must grant ~31 days once; "
        "a double grant lands near 62 days. "
        f"got stockVipExpireTime={entitlement.stockVipExpireTime}, "
        f"single-grant window=[{start + 31 * DAY_MILLIS}, {one_grant_ceiling})"
    )


def test_hot_lookup_columns_are_indexed(tmp_path):
    import sqlite3

    db_path = str(tmp_path / "test.db")
    create_app(db_path)
    connection = sqlite3.connect(db_path)
    try:
        index_names = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'index'"
            )
        }
    finally:
        connection.close()

    for expected in (
        "idx_orders_user_id",
        "idx_orders_provider_tx",
        "idx_payment_callbacks_order_id",
    ):
        assert expected in index_names, (
            f"hot lookup column index {expected} is missing; "
            f"existing indexes: {sorted(index_names)}"
        )


def test_refresh_token_is_single_use_and_rotates(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    rotated = client.post(
        "/v1/auth/refresh",
        json={"refreshToken": auth["refreshToken"], "deviceId": "device-1"},
    )
    assert rotated.status_code == 200

    replay = client.post(
        "/v1/auth/refresh",
        json={"refreshToken": auth["refreshToken"], "deviceId": "device-1"},
    )
    assert replay.status_code == 401, (
        "the original refresh token must be revoked once it has been used; "
        f"got status={replay.status_code}"
    )

    again = client.post(
        "/v1/auth/refresh",
        json={"refreshToken": rotated.json()["refreshToken"], "deviceId": "device-1"},
    )
    assert again.status_code == 200, "the freshly issued refresh token must still work"


def test_expired_refresh_token_is_rejected(tmp_path, monkeypatch):
    monkeypatch.setattr("app.main.REFRESH_TOKEN_TTL_MILLIS", -1000)
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    response = client.post(
        "/v1/auth/refresh",
        json={"refreshToken": auth["refreshToken"], "deviceId": "device-1"},
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "refresh token expired"


def test_logout_revokes_the_access_and_refresh_tokens(tmp_path):
    client = TestClient(create_app(str(tmp_path / "test.db")))
    auth = register(client)

    assert (
        client.get("/v1/me/entitlements", headers=auth_headers(auth["accessToken"])).status_code
        == 200
    )

    logout = client.post("/v1/auth/logout", headers=auth_headers(auth["accessToken"]))
    assert logout.status_code == 200
    assert logout.json()["status"] == "logged_out"

    assert (
        client.get("/v1/me/entitlements", headers=auth_headers(auth["accessToken"])).status_code
        == 401
    ), "the access token must stop working after logout"

    refresh = client.post(
        "/v1/auth/refresh",
        json={"refreshToken": auth["refreshToken"], "deviceId": "device-1"},
    )
    assert refresh.status_code == 401, "the refresh token must stop working after logout"


def test_sessions_table_migrates_when_refresh_expiry_column_is_missing(tmp_path):
    import sqlite3

    db_path = str(tmp_path / "legacy.db")
    # A database created before refresh_expires_at existed: opening the app
    # against it must add the column, not crash on the next session INSERT.
    legacy = sqlite3.connect(db_path)
    legacy.executescript(
        """
        CREATE TABLE sessions (
            refresh_token TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            access_token TEXT NOT NULL UNIQUE,
            device_id TEXT NOT NULL,
            expires_at INTEGER NOT NULL
        );
        """
    )
    legacy.close()

    client = TestClient(create_app(db_path))
    auth = register(client)
    assert auth["userId"].startswith("usr_")

    connection = sqlite3.connect(db_path)
    try:
        columns = {row[1] for row in connection.execute("PRAGMA table_info(sessions)")}
    finally:
        connection.close()
    assert "refresh_expires_at" in columns
