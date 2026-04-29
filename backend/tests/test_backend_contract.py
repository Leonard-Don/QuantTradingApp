import hashlib
import hmac

from fastapi.testclient import TestClient

from app.main import create_app


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
    assert status.json()["entitlement"]["stockVipExpireTime"] == paid.json()["stockVipExpireTime"]


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

    message = f"{order['orderId']}:provider-tx-signature:{order['amountCents']}:PAID"
    signature = hmac.new(b"secret", message.encode("utf-8"), hashlib.sha256).hexdigest()
    signed = client.post(
        "/v1/payment/callbacks/WECHAT",
        json={
            "orderId": order["orderId"],
            "providerTransactionId": "provider-tx-signature",
            "amountCents": order["amountCents"],
            "signature": signature,
        },
    )
    assert signed.status_code == 200
    assert signed.json()["status"] == "PAID"


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
