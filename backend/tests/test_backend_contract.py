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
