from __future__ import annotations

import hashlib
import os
import secrets
import sqlite3
import time
import uuid
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

DAY_MILLIS = 24 * 60 * 60 * 1000
ACCESS_TOKEN_TTL_MILLIS = 2 * 60 * 60 * 1000
GRACE_MILLIS = 7 * DAY_MILLIS


class RegisterRequest(BaseModel):
    displayName: str = "本机用户"
    phone: str
    password: str
    deviceId: str


class LoginRequest(BaseModel):
    phone: str
    password: str
    deviceId: str


class RefreshRequest(BaseModel):
    refreshToken: str
    deviceId: str


class AuthResponse(BaseModel):
    userId: str
    accessToken: str
    refreshToken: str
    expiresAt: int


class EntitlementResponse(BaseModel):
    userId: str
    serverTime: int
    stockVipExpireTime: int
    quantVipExpireTime: int
    graceUntil: int
    source: str


class OrderRequest(BaseModel):
    tier: str = Field(pattern="^(STOCK|QUANT|FULL)$")
    durationDays: int = Field(gt=0, le=370)
    channel: str = Field(pattern="^(WECHAT|ALIPAY)$")
    clientOrderId: str
    deviceId: str


class PaymentPayload(BaseModel):
    sandbox: bool
    orderId: str
    channel: str
    note: str


class OrderResponse(BaseModel):
    orderId: str
    tier: str
    durationDays: int
    amountCents: int
    currency: str
    channel: str
    status: str
    paymentPayload: PaymentPayload


class OrderStatusResponse(BaseModel):
    orderId: str
    status: str
    paidAt: Optional[int]
    entitlement: Optional[dict[str, int]]


class PaymentCallbackRequest(BaseModel):
    orderId: str
    providerTransactionId: str
    amountCents: int
    sandboxApproved: bool = True


class PaymentCallbackResponse(BaseModel):
    orderId: str
    status: str
    stockVipExpireTime: int
    quantVipExpireTime: int


class BackendStore:
    def __init__(self, db_path: str):
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_schema(self) -> None:
        with self.connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    phone TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    salt TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS sessions (
                    refresh_token TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    access_token TEXT NOT NULL UNIQUE,
                    device_id TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS entitlements (
                    user_id TEXT PRIMARY KEY,
                    stock_vip_expire_time INTEGER NOT NULL,
                    quant_vip_expire_time INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS orders (
                    order_id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    tier TEXT NOT NULL,
                    duration_days INTEGER NOT NULL,
                    amount_cents INTEGER NOT NULL,
                    currency TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    client_order_id TEXT NOT NULL UNIQUE,
                    device_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    provider_transaction_id TEXT,
                    created_at INTEGER NOT NULL,
                    paid_at INTEGER
                );
                """
            )

    def create_user(self, display_name: str, phone: str, password: str, device_id: str) -> AuthResponse:
        salt = secrets.token_hex(16)
        user_id = f"usr_{uuid.uuid4().hex}"
        now = now_millis()
        try:
            with self.connect() as conn:
                conn.execute(
                    "INSERT INTO users(id, display_name, phone, password_hash, salt, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    (user_id, display_name or "本机用户", phone, hash_password(password, salt), salt, now),
                )
                conn.execute(
                    "INSERT INTO entitlements(user_id, stock_vip_expire_time, quant_vip_expire_time, source, updated_at) VALUES (?, 0, 0, ?, ?)",
                    (user_id, "register", now),
                )
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="phone already registered") from exc
        return self.create_session(user_id, device_id)

    def login(self, phone: str, password: str, device_id: str) -> AuthResponse:
        with self.connect() as conn:
            user = conn.execute("SELECT * FROM users WHERE phone = ?", (phone,)).fetchone()
        if user is None or user["password_hash"] != hash_password(password, user["salt"]):
            raise HTTPException(status_code=401, detail="invalid phone or password")
        return self.create_session(user["id"], device_id)

    def create_session(self, user_id: str, device_id: str) -> AuthResponse:
        access_token = f"atk_{secrets.token_urlsafe(32)}"
        refresh_token = f"rtk_{secrets.token_urlsafe(32)}"
        expires_at = now_millis() + ACCESS_TOKEN_TTL_MILLIS
        with self.connect() as conn:
            conn.execute(
                "INSERT INTO sessions(refresh_token, user_id, access_token, device_id, expires_at) VALUES (?, ?, ?, ?, ?)",
                (refresh_token, user_id, access_token, device_id, expires_at),
            )
        return AuthResponse(
            userId=user_id,
            accessToken=access_token,
            refreshToken=refresh_token,
            expiresAt=expires_at,
        )

    def refresh_session(self, refresh_token: str, device_id: str) -> AuthResponse:
        with self.connect() as conn:
            session = conn.execute(
                "SELECT * FROM sessions WHERE refresh_token = ? AND device_id = ?",
                (refresh_token, device_id),
            ).fetchone()
        if session is None:
            raise HTTPException(status_code=401, detail="invalid refresh token")
        return self.create_session(session["user_id"], device_id)

    def require_user(self, authorization: Optional[str]) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="missing bearer token")
        token = authorization.removeprefix("Bearer ").strip()
        with self.connect() as conn:
            session = conn.execute(
                "SELECT * FROM sessions WHERE access_token = ?",
                (token,),
            ).fetchone()
        if session is None or session["expires_at"] < now_millis():
            raise HTTPException(status_code=401, detail="expired or invalid token")
        return session["user_id"]

    def entitlements(self, user_id: str) -> EntitlementResponse:
        with self.connect() as conn:
            row = conn.execute("SELECT * FROM entitlements WHERE user_id = ?", (user_id,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="entitlement not found")
        server_time = now_millis()
        latest_expiry = max(row["stock_vip_expire_time"], row["quant_vip_expire_time"])
        return EntitlementResponse(
            userId=user_id,
            serverTime=server_time,
            stockVipExpireTime=row["stock_vip_expire_time"],
            quantVipExpireTime=row["quant_vip_expire_time"],
            graceUntil=latest_expiry + GRACE_MILLIS if latest_expiry > 0 else 0,
            source=row["source"],
        )

    def create_order(self, user_id: str, request: OrderRequest) -> OrderResponse:
        order_id = f"ord_{uuid.uuid4().hex}"
        amount_cents = price_for(request.tier, request.durationDays)
        with self.connect() as conn:
            try:
                conn.execute(
                    """
                    INSERT INTO orders(order_id, user_id, tier, duration_days, amount_cents, currency, channel,
                    client_order_id, device_id, status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'CNY', ?, ?, ?, 'PENDING', ?)
                    """,
                    (
                        order_id,
                        user_id,
                        request.tier,
                        request.durationDays,
                        amount_cents,
                        request.channel,
                        request.clientOrderId,
                        request.deviceId,
                        now_millis(),
                    ),
                )
            except sqlite3.IntegrityError as exc:
                raise HTTPException(status_code=409, detail="duplicate client order id") from exc
        return OrderResponse(
            orderId=order_id,
            tier=request.tier,
            durationDays=request.durationDays,
            amountCents=amount_cents,
            currency="CNY",
            channel=request.channel,
            status="PENDING",
            paymentPayload=PaymentPayload(
                sandbox=True,
                orderId=order_id,
                channel=request.channel,
                note="Sandbox payload. Replace with provider SDK payload after merchant setup.",
            ),
        )

    def order_status(self, user_id: str, order_id: str) -> OrderStatusResponse:
        with self.connect() as conn:
            order = conn.execute(
                "SELECT * FROM orders WHERE user_id = ? AND order_id = ?",
                (user_id, order_id),
            ).fetchone()
        if order is None:
            raise HTTPException(status_code=404, detail="order not found")
        entitlement = None
        if order["status"] == "PAID":
            current = self.entitlements(user_id)
            entitlement = {
                "stockVipExpireTime": current.stockVipExpireTime,
                "quantVipExpireTime": current.quantVipExpireTime,
            }
        return OrderStatusResponse(
            orderId=order_id,
            status=order["status"],
            paidAt=order["paid_at"],
            entitlement=entitlement,
        )

    def sandbox_payment_callback(self, channel: str, request: PaymentCallbackRequest) -> PaymentCallbackResponse:
        if not request.sandboxApproved:
            raise HTTPException(status_code=400, detail="sandbox payment not approved")
        with self.connect() as conn:
            order = conn.execute("SELECT * FROM orders WHERE order_id = ?", (request.orderId,)).fetchone()
            if order is None:
                raise HTTPException(status_code=404, detail="order not found")
            if order["channel"] != channel:
                raise HTTPException(status_code=400, detail="payment channel mismatch")
            if order["amount_cents"] != request.amountCents:
                raise HTTPException(status_code=400, detail="payment amount mismatch")
            if order["status"] == "PAID":
                current = self.entitlements(order["user_id"])
                return PaymentCallbackResponse(
                    orderId=request.orderId,
                    status="PAID",
                    stockVipExpireTime=current.stockVipExpireTime,
                    quantVipExpireTime=current.quantVipExpireTime,
                )

            paid_at = now_millis()
            current = conn.execute(
                "SELECT * FROM entitlements WHERE user_id = ?",
                (order["user_id"],),
            ).fetchone()
            stock_expiry, quant_expiry = extend_entitlement(
                tier=order["tier"],
                days=order["duration_days"],
                now=paid_at,
                stock_expiry=current["stock_vip_expire_time"],
                quant_expiry=current["quant_vip_expire_time"],
            )
            conn.execute(
                """
                UPDATE entitlements
                SET stock_vip_expire_time = ?, quant_vip_expire_time = ?, source = 'payment_order', updated_at = ?
                WHERE user_id = ?
                """,
                (stock_expiry, quant_expiry, paid_at, order["user_id"]),
            )
            conn.execute(
                "UPDATE orders SET status = 'PAID', provider_transaction_id = ?, paid_at = ? WHERE order_id = ?",
                (request.providerTransactionId, paid_at, request.orderId),
            )
        return PaymentCallbackResponse(
            orderId=request.orderId,
            status="PAID",
            stockVipExpireTime=stock_expiry,
            quantVipExpireTime=quant_expiry,
        )


def create_app(db_path: Optional[str] = None) -> FastAPI:
    resolved_db_path = db_path or os.getenv("TIANXIAN_DB_PATH", "backend/data/tianxian.db")
    store = BackendStore(resolved_db_path)
    app = FastAPI(title="TianXianQuant Backend", version="0.1.0")

    def current_user(authorization: Optional[str] = Header(default=None)) -> str:
        return store.require_user(authorization)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/v1/auth/register", response_model=AuthResponse)
    def register(request: RegisterRequest) -> AuthResponse:
        return store.create_user(request.displayName, request.phone, request.password, request.deviceId)

    @app.post("/v1/auth/login", response_model=AuthResponse)
    def login(request: LoginRequest) -> AuthResponse:
        return store.login(request.phone, request.password, request.deviceId)

    @app.post("/v1/auth/refresh", response_model=AuthResponse)
    def refresh(request: RefreshRequest) -> AuthResponse:
        return store.refresh_session(request.refreshToken, request.deviceId)

    @app.get("/v1/me/entitlements", response_model=EntitlementResponse)
    def entitlements(user_id: str = Depends(current_user)) -> EntitlementResponse:
        return store.entitlements(user_id)

    @app.post("/v1/orders", response_model=OrderResponse)
    def create_order(request: OrderRequest, user_id: str = Depends(current_user)) -> OrderResponse:
        return store.create_order(user_id, request)

    @app.get("/v1/orders/{order_id}", response_model=OrderStatusResponse)
    def order_status(order_id: str, user_id: str = Depends(current_user)) -> OrderStatusResponse:
        return store.order_status(user_id, order_id)

    @app.post("/v1/payment/callbacks/{channel}", response_model=PaymentCallbackResponse)
    def payment_callback(channel: str, request: PaymentCallbackRequest) -> PaymentCallbackResponse:
        normalized_channel = channel.upper()
        if normalized_channel not in {"WECHAT", "ALIPAY"}:
            raise HTTPException(status_code=404, detail="unknown payment channel")
        return store.sandbox_payment_callback(normalized_channel, request)

    return app


def now_millis() -> int:
    return int(time.time() * 1000)


def hash_password(password: str, salt: str) -> str:
    return hashlib.sha256(f"{salt}:{password}".encode("utf-8")).hexdigest()


def price_for(tier: str, duration_days: int) -> int:
    if tier == "STOCK":
        if duration_days >= 360:
            return 58_800
        if duration_days >= 90:
            return 16_800
        return 6_800
    if tier == "QUANT":
        return 16_800
    if tier == "FULL":
        return 58_800
    raise HTTPException(status_code=400, detail="unknown tier")


def extend_entitlement(
    tier: str,
    days: int,
    now: int,
    stock_expiry: int,
    quant_expiry: int,
) -> tuple[int, int]:
    duration = days * DAY_MILLIS

    def extend_one(current_expiry: int) -> int:
        return max(current_expiry, now) + duration

    if tier == "STOCK":
        return extend_one(stock_expiry), quant_expiry
    if tier == "QUANT":
        return stock_expiry, extend_one(quant_expiry)
    if tier == "FULL":
        return extend_one(stock_expiry), extend_one(quant_expiry)
    raise HTTPException(status_code=400, detail="unknown tier")


app = create_app()
