from __future__ import annotations

import asyncio
import collections
import hashlib
import hmac
import json
import logging
import os
import secrets
import sqlite3
import time
import uuid
from html import escape
from pathlib import Path
from typing import Any, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field
from starlette.middleware.base import BaseHTTPMiddleware

DAY_MILLIS = 24 * 60 * 60 * 1000
ACCESS_TOKEN_TTL_MILLIS = 2 * 60 * 60 * 1000
REFRESH_TOKEN_TTL_MILLIS = 30 * DAY_MILLIS
GRACE_MILLIS = 7 * DAY_MILLIS
CALLBACK_TIMESTAMP_WINDOW_MILLIS = 5 * 60 * 1000
DEFAULT_DB_PATH = Path(__file__).resolve().parents[1] / "data" / "quanttrading.db"

DEFAULT_RATE_LIMIT_RULES: dict[str, tuple[int, int]] = {
    "/v1/auth/register": (5, 60),
    "/v1/auth/login": (10, 60),
    "/v1/auth/refresh": (30, 60),
    "/v1/payment/callbacks/": (60, 60),
}

logger = logging.getLogger("quanttrading")


class JsonLogFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": int(record.created * 1000),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        for key in ("event", "method", "path", "status", "duration_ms", "client", "error"):
            value = getattr(record, key, None)
            if value is not None:
                payload[key] = value
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def _configure_logging() -> None:
    if getattr(_configure_logging, "_done", False):
        return
    handler = logging.StreamHandler()
    handler.setFormatter(JsonLogFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(os.getenv("QUANTTRADING_LOG_LEVEL", "INFO").upper())
    _configure_logging._done = True  # type: ignore[attr-defined]


class StructuredLoggingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        start = time.time()
        try:
            response = await call_next(request)
        except Exception as exc:
            logger.error(
                "request_error",
                extra={
                    "event": "request_error",
                    "method": request.method,
                    "path": request.url.path,
                    "duration_ms": int((time.time() - start) * 1000),
                    "error": repr(exc),
                    "client": request.client.host if request.client else None,
                },
                exc_info=True,
            )
            raise
        logger.info(
            "request",
            extra={
                "event": "request",
                "method": request.method,
                "path": request.url.path,
                "status": response.status_code,
                "duration_ms": int((time.time() - start) * 1000),
                "client": request.client.host if request.client else None,
            },
        )
        return response


class RateLimitMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, rules: dict[str, tuple[int, int]]):
        super().__init__(app)
        self._rules = rules
        self._buckets: dict[tuple[str, str], collections.deque[float]] = {}
        self._lock = asyncio.Lock()

    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        rule: Optional[tuple[str, tuple[int, int]]] = None
        for prefix, value in self._rules.items():
            if path.startswith(prefix):
                rule = (prefix, value)
                break
        if rule is None:
            return await call_next(request)
        prefix, (limit, window_seconds) = rule
        client = request.client.host if request.client else "unknown"
        now = time.time()
        async with self._lock:
            bucket = self._buckets.setdefault((client, prefix), collections.deque())
            cutoff = now - window_seconds
            while bucket and bucket[0] < cutoff:
                bucket.popleft()
            if len(bucket) >= limit:
                retry_after = max(1, int(bucket[0] + window_seconds - now))
                logger.warning(
                    "rate_limited",
                    extra={
                        "event": "rate_limited",
                        "path": path,
                        "client": client,
                    },
                )
                return JSONResponse(
                    status_code=429,
                    content={"detail": "too many requests"},
                    headers={"Retry-After": str(retry_after)},
                )
            bucket.append(now)
        return await call_next(request)


def _allowed_cors_origins() -> list[str]:
    raw = os.getenv("QUANTTRADING_CORS_ALLOW_ORIGINS", "").strip()
    if not raw:
        return []
    return [origin.strip() for origin in raw.split(",") if origin.strip()]


class RegisterRequest(BaseModel):
    displayName: str = "本机用户"
    phone: str = Field(pattern=r"^\d{11}$")
    password: str = Field(min_length=6, max_length=128)
    deviceId: str = Field(min_length=3, max_length=128)


class LoginRequest(BaseModel):
    phone: str = Field(pattern=r"^\d{11}$")
    password: str = Field(min_length=6, max_length=128)
    deviceId: str = Field(min_length=3, max_length=128)


class RefreshRequest(BaseModel):
    refreshToken: str = Field(min_length=8)
    deviceId: str = Field(min_length=3, max_length=128)


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
    deviceId: str = Field(min_length=3, max_length=128)


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
    tier: str
    durationDays: int
    amountCents: int
    currency: str
    channel: str
    status: str
    createdAt: int
    paidAt: Optional[int]
    entitlement: Optional[dict[str, int]]


class PaymentCallbackRequest(BaseModel):
    orderId: str
    providerTransactionId: str
    amountCents: int
    sandboxApproved: bool = True
    eventType: str = Field(default="PAID", pattern="^(PAID|REFUNDED|CANCELLED)$")
    timestamp: Optional[int] = None
    signature: Optional[str] = None


class PaymentCallbackResponse(BaseModel):
    orderId: str
    status: str
    stockVipExpireTime: int
    quantVipExpireTime: int


class MarketProxyResponse(BaseModel):
    source: str
    sourceUpdatedAt: int
    status: str
    data: list[dict]
    disclaimer: str
    message: str


class AdminAuditResponse(BaseModel):
    generatedAt: int
    counts: dict[str, int]
    orderStatusCounts: dict[str, int]
    recentUsers: list[dict[str, Any]]
    recentOrders: list[dict[str, Any]]
    recentPaymentCallbacks: list[dict[str, Any]]
    entitlements: list[dict[str, Any]]


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
                    expires_at INTEGER NOT NULL,
                    refresh_expires_at INTEGER NOT NULL DEFAULT 0
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

                CREATE TABLE IF NOT EXISTS payment_callbacks (
                    id TEXT PRIMARY KEY,
                    order_id TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    provider_transaction_id TEXT NOT NULL,
                    amount_cents INTEGER NOT NULL,
                    accepted INTEGER NOT NULL,
                    detail TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
                CREATE INDEX IF NOT EXISTS idx_orders_provider_tx
                    ON orders(provider_transaction_id);
                CREATE INDEX IF NOT EXISTS idx_payment_callbacks_order_id
                    ON payment_callbacks(order_id);
                """
            )
            self._migrate_sessions_refresh_expiry(conn)

    def _migrate_sessions_refresh_expiry(self, conn: sqlite3.Connection) -> None:
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(sessions)")}
        if "refresh_expires_at" not in columns:
            conn.execute(
                "ALTER TABLE sessions ADD COLUMN refresh_expires_at INTEGER NOT NULL DEFAULT 0"
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
        now = now_millis()
        expires_at = now + ACCESS_TOKEN_TTL_MILLIS
        refresh_expires_at = now + REFRESH_TOKEN_TTL_MILLIS
        with self.connect() as conn:
            # Drop this user's fully expired sessions so the table cannot grow
            # without bound across repeated logins.
            conn.execute(
                "DELETE FROM sessions WHERE user_id = ? AND refresh_expires_at < ?",
                (user_id, now),
            )
            conn.execute(
                "INSERT INTO sessions(refresh_token, user_id, access_token, device_id, "
                "expires_at, refresh_expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                (refresh_token, user_id, access_token, device_id, expires_at, refresh_expires_at),
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
            if session["refresh_expires_at"] < now_millis():
                raise HTTPException(status_code=401, detail="refresh token expired")
            # A refresh token is single-use: delete it so a leaked or replayed
            # token cannot mint a second session.
            conn.execute("DELETE FROM sessions WHERE refresh_token = ?", (refresh_token,))
        return self.create_session(session["user_id"], device_id)

    def logout(self, authorization: Optional[str]) -> dict[str, str]:
        user_id = self.require_user(authorization)
        token = (authorization or "").removeprefix("Bearer ").strip()
        with self.connect() as conn:
            conn.execute("DELETE FROM sessions WHERE access_token = ?", (token,))
        return {"status": "logged_out", "userId": user_id}

    def delete_user(self, user_id: str) -> dict[str, str]:
        with self.connect() as conn:
            conn.execute(
                "DELETE FROM payment_callbacks WHERE order_id IN (SELECT order_id FROM orders WHERE user_id = ?)",
                (user_id,),
            )
            conn.execute("DELETE FROM orders WHERE user_id = ?", (user_id,))
            conn.execute("DELETE FROM entitlements WHERE user_id = ?", (user_id,))
            conn.execute("DELETE FROM sessions WHERE user_id = ?", (user_id,))
            conn.execute("DELETE FROM users WHERE id = ?", (user_id,))
        return {"status": "deleted"}

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

    def require_active_vip(self, user_id: str) -> EntitlementResponse:
        entitlement = self.entitlements(user_id)
        active_until = max(
            entitlement.stockVipExpireTime,
            entitlement.quantVipExpireTime,
            entitlement.graceUntil,
        )
        if entitlement.serverTime >= active_until:
            raise HTTPException(status_code=403, detail="active vip required")
        return entitlement

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
        return self._order_status_response(user_id, order)

    def list_orders(self, user_id: str, limit: int = 20) -> list[OrderStatusResponse]:
        safe_limit = max(1, min(limit, 100))
        with self.connect() as conn:
            orders = conn.execute(
                """
                SELECT * FROM orders
                WHERE user_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (user_id, safe_limit),
            ).fetchall()
        return [self._order_status_response(user_id, order) for order in orders]

    def admin_audit_snapshot(self, limit: int = 20) -> AdminAuditResponse:
        safe_limit = max(1, min(limit, 100))
        generated_at = now_millis()
        with self.connect() as conn:
            counts = {
                "users": count_rows(conn, "SELECT COUNT(*) FROM users"),
                "orders": count_rows(conn, "SELECT COUNT(*) FROM orders"),
                "paymentCallbacks": count_rows(conn, "SELECT COUNT(*) FROM payment_callbacks"),
                "activeEntitlements": count_rows(
                    conn,
                    """
                    SELECT COUNT(*) FROM entitlements
                    WHERE stock_vip_expire_time > ? OR quant_vip_expire_time > ?
                    """,
                    (generated_at, generated_at),
                ),
            }
            status_counts = {
                row["status"]: row["count"]
                for row in conn.execute(
                    "SELECT status, COUNT(*) AS count FROM orders GROUP BY status ORDER BY status"
                ).fetchall()
            }
            recent_users = rows_to_dicts(
                conn.execute(
                    """
                    SELECT id, display_name, phone, created_at
                    FROM users
                    ORDER BY created_at DESC
                    LIMIT ?
                    """,
                    (safe_limit,),
                ).fetchall()
            )
            recent_orders = rows_to_dicts(
                conn.execute(
                    """
                    SELECT order_id, user_id, tier, duration_days, amount_cents, currency, channel,
                           client_order_id, device_id, status, provider_transaction_id, created_at, paid_at
                    FROM orders
                    ORDER BY created_at DESC
                    LIMIT ?
                    """,
                    (safe_limit,),
                ).fetchall()
            )
            recent_callbacks = rows_to_dicts(
                conn.execute(
                    """
                    SELECT id, order_id, channel, event_type, provider_transaction_id,
                           amount_cents, accepted, detail, created_at
                    FROM payment_callbacks
                    ORDER BY created_at DESC
                    LIMIT ?
                    """,
                    (safe_limit,),
                ).fetchall()
            )
            entitlements = rows_to_dicts(
                conn.execute(
                    """
                    SELECT user_id, stock_vip_expire_time, quant_vip_expire_time, source, updated_at
                    FROM entitlements
                    ORDER BY updated_at DESC
                    LIMIT ?
                    """,
                    (safe_limit,),
                ).fetchall()
            )
        return AdminAuditResponse(
            generatedAt=generated_at,
            counts=counts,
            orderStatusCounts=status_counts,
            recentUsers=recent_users,
            recentOrders=recent_orders,
            recentPaymentCallbacks=recent_callbacks,
            entitlements=entitlements,
        )

    def _order_status_response(self, user_id: str, order: sqlite3.Row) -> OrderStatusResponse:
        entitlement = None
        if order["status"] == "PAID":
            current = self.entitlements(user_id)
            entitlement = {
                "stockVipExpireTime": current.stockVipExpireTime,
                "quantVipExpireTime": current.quantVipExpireTime,
            }
        return OrderStatusResponse(
            orderId=order["order_id"],
            tier=order["tier"],
            durationDays=order["duration_days"],
            amountCents=order["amount_cents"],
            currency=order["currency"],
            channel=order["channel"],
            status=order["status"],
            createdAt=order["created_at"],
            paidAt=order["paid_at"],
            entitlement=entitlement,
        )

    def sandbox_payment_callback(self, channel: str, request: PaymentCallbackRequest) -> PaymentCallbackResponse:
        if not request.sandboxApproved:
            raise HTTPException(status_code=400, detail="sandbox payment not approved")
        with self.connect() as conn:
            # Serialize the whole read-check-write so two concurrent callbacks
            # for the same order cannot both pass the status / duplicate-tx
            # checks and double grant entitlements. BEGIN IMMEDIATE takes the
            # write lock up front; a concurrent callback blocks here until this
            # one commits, then observes the already-updated PAID status.
            conn.execute("BEGIN IMMEDIATE")
            order = conn.execute("SELECT * FROM orders WHERE order_id = ?", (request.orderId,)).fetchone()
            if order is None:
                raise HTTPException(status_code=404, detail="order not found")
            if order["channel"] != channel:
                self._record_callback(conn, request, channel, accepted=False, detail="channel_mismatch")
                conn.commit()
                raise HTTPException(status_code=400, detail="payment channel mismatch")
            if order["amount_cents"] != request.amountCents:
                self._record_callback(conn, request, channel, accepted=False, detail="amount_mismatch")
                conn.commit()
                raise HTTPException(status_code=400, detail="payment amount mismatch")
            try:
                verify_callback_signature(request)
            except HTTPException as exc:
                self._record_callback(
                    conn,
                    request,
                    channel,
                    accepted=False,
                    detail=f"signature:{exc.detail}",
                )
                conn.commit()
                raise

            if request.eventType == "PAID":
                return self._mark_order_paid(conn, order, request)
            if request.eventType in {"REFUNDED", "CANCELLED"}:
                return self._mark_order_reversed(conn, order, request)
            raise HTTPException(status_code=400, detail="unsupported payment event")

    def _mark_order_paid(
        self,
        conn: sqlite3.Connection,
        order: sqlite3.Row,
        request: PaymentCallbackRequest,
    ) -> PaymentCallbackResponse:
        if order["status"] == "PAID":
            current = self.entitlements(order["user_id"])
            self._record_callback(conn, request, order["channel"], accepted=True, detail="idempotent paid callback")
            return PaymentCallbackResponse(
                orderId=request.orderId,
                status="PAID",
                stockVipExpireTime=current.stockVipExpireTime,
                quantVipExpireTime=current.quantVipExpireTime,
            )
        if order["status"] in {"REFUNDED", "CANCELLED"}:
            raise HTTPException(status_code=400, detail="order already reversed")

        duplicate_tx = conn.execute(
            "SELECT order_id FROM orders WHERE provider_transaction_id = ? AND order_id != ?",
            (request.providerTransactionId, request.orderId),
        ).fetchone()
        if duplicate_tx is not None:
            raise HTTPException(status_code=409, detail="duplicate provider transaction id")

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
        self._record_callback(conn, request, order["channel"], accepted=True, detail="paid")
        return PaymentCallbackResponse(
            orderId=request.orderId,
            status="PAID",
            stockVipExpireTime=stock_expiry,
            quantVipExpireTime=quant_expiry,
        )

    def _mark_order_reversed(
        self,
        conn: sqlite3.Connection,
        order: sqlite3.Row,
        request: PaymentCallbackRequest,
    ) -> PaymentCallbackResponse:
        if order["status"] == request.eventType:
            current = self.entitlements(order["user_id"])
            self._record_callback(
                conn,
                request,
                order["channel"],
                accepted=True,
                detail=f"idempotent {request.eventType.lower()} callback",
            )
            return PaymentCallbackResponse(
                orderId=request.orderId,
                status=request.eventType,
                stockVipExpireTime=current.stockVipExpireTime,
                quantVipExpireTime=current.quantVipExpireTime,
            )
        if order["status"] == "PENDING" and request.eventType == "CANCELLED":
            conn.execute(
                "UPDATE orders SET status = 'CANCELLED', provider_transaction_id = ? WHERE order_id = ?",
                (request.providerTransactionId, request.orderId),
            )
            current = self.entitlements(order["user_id"])
            self._record_callback(conn, request, order["channel"], accepted=True, detail="pending order cancelled")
            return PaymentCallbackResponse(
                orderId=request.orderId,
                status="CANCELLED",
                stockVipExpireTime=current.stockVipExpireTime,
                quantVipExpireTime=current.quantVipExpireTime,
            )
        if order["status"] != "PAID":
            raise HTTPException(status_code=400, detail="order is not paid")

        current = conn.execute(
            "SELECT * FROM entitlements WHERE user_id = ?",
            (order["user_id"],),
        ).fetchone()
        stock_expiry, quant_expiry = reduce_entitlement(
            tier=order["tier"],
            days=order["duration_days"],
            stock_expiry=current["stock_vip_expire_time"],
            quant_expiry=current["quant_vip_expire_time"],
        )
        now = now_millis()
        conn.execute(
            """
            UPDATE entitlements
            SET stock_vip_expire_time = ?, quant_vip_expire_time = ?, source = ?, updated_at = ?
            WHERE user_id = ?
            """,
            (stock_expiry, quant_expiry, request.eventType.lower(), now, order["user_id"]),
        )
        conn.execute(
            "UPDATE orders SET status = ?, provider_transaction_id = ? WHERE order_id = ?",
            (request.eventType, request.providerTransactionId, request.orderId),
        )
        self._record_callback(conn, request, order["channel"], accepted=True, detail=request.eventType.lower())
        return PaymentCallbackResponse(
            orderId=request.orderId,
            status=request.eventType,
            stockVipExpireTime=stock_expiry,
            quantVipExpireTime=quant_expiry,
        )

    def _record_callback(
        self,
        conn: sqlite3.Connection,
        request: PaymentCallbackRequest,
        channel: str,
        accepted: bool,
        detail: str,
    ) -> None:
        conn.execute(
            """
            INSERT INTO payment_callbacks(id, order_id, channel, event_type, provider_transaction_id,
            amount_cents, accepted, detail, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                f"pcb_{uuid.uuid4().hex}",
                request.orderId,
                channel,
                request.eventType,
                request.providerTransactionId,
                request.amountCents,
                1 if accepted else 0,
                detail,
                now_millis(),
            ),
        )

    def market_proxy_not_configured(self, endpoint: str) -> MarketProxyResponse:
        return MarketProxyResponse(
            source="licensed-provider-not-configured",
            sourceUpdatedAt=now_millis(),
            status="not_configured",
            data=[],
            disclaimer="研究参考，不构成投资建议。正式付费版本需接入授权数据源后展示。",
            message=f"{endpoint} requires a licensed market-data provider and backend proxy credentials.",
        )


def require_admin_token(
    token: Optional[str] = Query(default=None),
    x_admin_token: Optional[str] = Header(default=None),
) -> None:
    expected_token = os.getenv("QUANTTRADING_ADMIN_TOKEN", "").strip()
    if not expected_token:
        raise HTTPException(status_code=403, detail="admin disabled")
    supplied_token = (x_admin_token or token or "").strip()
    if not secrets.compare_digest(expected_token, supplied_token):
        raise HTTPException(status_code=401, detail="invalid admin token")


def count_rows(conn: sqlite3.Connection, sql: str, params: tuple[Any, ...] = ()) -> int:
    return int(conn.execute(sql, params).fetchone()[0])


def rows_to_dicts(rows: list[sqlite3.Row]) -> list[dict[str, Any]]:
    return [{key: row[key] for key in row.keys()} for row in rows]


def render_admin_audit_html(snapshot: AdminAuditResponse) -> str:
    status_rows = [
        {"status": status, "count": count}
        for status, count in sorted(snapshot.orderStatusCounts.items())
    ]
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>QuantTradingApp Admin Audit</title>
  <style>
    :root {{
      color-scheme: light;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: #f6f7f9;
      color: #17202a;
    }}
    body {{ margin: 0; }}
    main {{ max-width: 1180px; margin: 0 auto; padding: 32px 20px 48px; }}
    h1 {{ margin: 0 0 6px; font-size: 28px; }}
    h2 {{ margin: 28px 0 12px; font-size: 18px; }}
    .muted {{ color: #637083; }}
    .counts {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-top: 22px; }}
    .metric {{ border: 1px solid #d9dee7; border-radius: 8px; background: #fff; padding: 14px; }}
    .metric span {{ display: block; color: #637083; font-size: 13px; }}
    .metric strong {{ display: block; margin-top: 8px; font-size: 24px; }}
    .table-wrap {{ overflow-x: auto; border: 1px solid #d9dee7; border-radius: 8px; background: #fff; }}
    table {{ width: 100%; border-collapse: collapse; font-size: 13px; }}
    th, td {{ padding: 10px 12px; border-bottom: 1px solid #edf0f4; text-align: left; white-space: nowrap; }}
    th {{ background: #f1f4f8; color: #39465a; font-weight: 650; }}
    tr:last-child td {{ border-bottom: 0; }}
    .empty {{ border: 1px dashed #cfd7e3; border-radius: 8px; padding: 14px; color: #637083; background: #fff; }}
  </style>
</head>
<body>
  <main>
    <h1>QuantTradingApp Admin Audit</h1>
    <div class="muted">Generated at {escape_cell(snapshot.generatedAt)}. This page is read-only.</div>
    <section class="counts">
      {render_metric("Users", snapshot.counts.get("users", 0))}
      {render_metric("Orders", snapshot.counts.get("orders", 0))}
      {render_metric("Payment callbacks", snapshot.counts.get("paymentCallbacks", 0))}
      {render_metric("Active entitlements", snapshot.counts.get("activeEntitlements", 0))}
    </section>
    {render_table("Order Status", status_rows, [("status", "Status"), ("count", "Count")])}
    {render_table("Recent Orders", snapshot.recentOrders, [
        ("order_id", "Order ID"),
        ("user_id", "User ID"),
        ("tier", "Tier"),
        ("status", "Status"),
        ("amount_cents", "Amount"),
        ("channel", "Channel"),
        ("created_at", "Created"),
        ("paid_at", "Paid"),
    ])}
    {render_table("Recent Payment Callbacks", snapshot.recentPaymentCallbacks, [
        ("order_id", "Order ID"),
        ("event_type", "Event"),
        ("provider_transaction_id", "Provider TX"),
        ("amount_cents", "Amount"),
        ("accepted", "Accepted"),
        ("detail", "Detail"),
        ("created_at", "Created"),
    ])}
    {render_table("Entitlements", snapshot.entitlements, [
        ("user_id", "User ID"),
        ("stock_vip_expire_time", "Stock VIP"),
        ("quant_vip_expire_time", "Quant VIP"),
        ("source", "Source"),
        ("updated_at", "Updated"),
    ])}
    {render_table("Recent Users", snapshot.recentUsers, [
        ("id", "User ID"),
        ("display_name", "Display Name"),
        ("phone", "Phone"),
        ("created_at", "Created"),
    ])}
  </main>
</body>
</html>"""


def render_metric(label: str, value: Any) -> str:
    return f"""<div class="metric"><span>{escape_cell(label)}</span><strong>{escape_cell(value)}</strong></div>"""


def render_table(title: str, rows: list[dict[str, Any]], columns: list[tuple[str, str]]) -> str:
    if not rows:
        return f"""<section><h2>{escape_cell(title)}</h2><div class="empty">No records</div></section>"""
    headers = "".join(f"<th>{escape_cell(label)}</th>" for _, label in columns)
    body = "".join(
        "<tr>"
        + "".join(f"<td>{escape_cell(row.get(key, ''))}</td>" for key, _ in columns)
        + "</tr>"
        for row in rows
    )
    return f"""<section><h2>{escape_cell(title)}</h2><div class="table-wrap"><table><thead><tr>{headers}</tr></thead><tbody>{body}</tbody></table></div></section>"""


def escape_cell(value: Any) -> str:
    if value is None:
        return ""
    return escape(str(value))


def create_app(
    db_path: Optional[str] = None,
    rate_limit_rules: Optional[dict[str, tuple[int, int]]] = None,
) -> FastAPI:
    _configure_logging()
    resolved_db_path = db_path or os.getenv("QUANTTRADING_DB_PATH", str(DEFAULT_DB_PATH))
    store = BackendStore(resolved_db_path)
    app = FastAPI(title="QuantTradingApp Backend", version="0.1.0")

    cors_origins = _allowed_cors_origins()
    if cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=cors_origins,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    rules = DEFAULT_RATE_LIMIT_RULES if rate_limit_rules is None else rate_limit_rules
    if rules:
        app.add_middleware(RateLimitMiddleware, rules=rules)
    app.add_middleware(StructuredLoggingMiddleware)

    def current_user(authorization: Optional[str] = Header(default=None)) -> str:
        return store.require_user(authorization)

    @app.get("/health")
    def health() -> JSONResponse:
        db_ok = True
        db_error: Optional[str] = None
        db_latency_ms: Optional[int] = None
        started = time.time()
        try:
            with store.connect() as conn:
                conn.execute("SELECT 1").fetchone()
            db_latency_ms = int((time.time() - started) * 1000)
        except Exception as exc:
            db_ok = False
            db_error = str(exc)
        body: dict[str, Any] = {
            "status": "ok" if db_ok else "degraded",
            "db": "ok" if db_ok else "error",
            "dbLatencyMs": db_latency_ms,
            "serverTime": now_millis(),
        }
        if db_error is not None:
            body["dbError"] = db_error
        return JSONResponse(status_code=200 if db_ok else 503, content=body)

    @app.post("/v1/auth/register", response_model=AuthResponse)
    def register(request: RegisterRequest) -> AuthResponse:
        return store.create_user(request.displayName, request.phone, request.password, request.deviceId)

    @app.post("/v1/auth/login", response_model=AuthResponse)
    def login(request: LoginRequest) -> AuthResponse:
        return store.login(request.phone, request.password, request.deviceId)

    @app.post("/v1/auth/refresh", response_model=AuthResponse)
    def refresh(request: RefreshRequest) -> AuthResponse:
        return store.refresh_session(request.refreshToken, request.deviceId)

    @app.post("/v1/auth/logout")
    def logout(authorization: Optional[str] = Header(default=None)) -> dict[str, str]:
        return store.logout(authorization)

    @app.get("/v1/me/entitlements", response_model=EntitlementResponse)
    def entitlements(user_id: str = Depends(current_user)) -> EntitlementResponse:
        return store.entitlements(user_id)

    @app.delete("/v1/me")
    def delete_me(user_id: str = Depends(current_user)) -> dict[str, str]:
        return store.delete_user(user_id)

    @app.post("/v1/orders", response_model=OrderResponse)
    def create_order(request: OrderRequest, user_id: str = Depends(current_user)) -> OrderResponse:
        return store.create_order(user_id, request)

    @app.get("/v1/orders/{order_id}", response_model=OrderStatusResponse)
    def order_status(order_id: str, user_id: str = Depends(current_user)) -> OrderStatusResponse:
        return store.order_status(user_id, order_id)

    @app.get("/v1/me/orders", response_model=list[OrderStatusResponse])
    def list_orders(limit: int = 20, user_id: str = Depends(current_user)) -> list[OrderStatusResponse]:
        return store.list_orders(user_id, limit=limit)

    @app.post("/v1/payment/callbacks/{channel}", response_model=PaymentCallbackResponse)
    def payment_callback(channel: str, request: PaymentCallbackRequest) -> PaymentCallbackResponse:
        normalized_channel = channel.upper()
        if normalized_channel not in {"WECHAT", "ALIPAY"}:
            raise HTTPException(status_code=404, detail="unknown payment channel")
        return store.sandbox_payment_callback(normalized_channel, request)

    @app.get("/v1/admin/audit", response_model=AdminAuditResponse)
    def admin_audit(
        limit: int = 20,
        _: None = Depends(require_admin_token),
    ) -> AdminAuditResponse:
        return store.admin_audit_snapshot(limit=limit)

    @app.get("/admin", response_class=HTMLResponse)
    def admin_page(
        limit: int = 20,
        _: None = Depends(require_admin_token),
    ) -> HTMLResponse:
        return HTMLResponse(render_admin_audit_html(store.admin_audit_snapshot(limit=limit)))

    @app.get("/v1/market/capital-flow", response_model=MarketProxyResponse)
    def capital_flow(codes: str, user_id: str = Depends(current_user)) -> MarketProxyResponse:
        store.require_active_vip(user_id)
        return store.market_proxy_not_configured(f"capital-flow:{codes}")

    @app.get("/v1/market/dragon-list", response_model=MarketProxyResponse)
    def dragon_list(date: str, user_id: str = Depends(current_user)) -> MarketProxyResponse:
        store.require_active_vip(user_id)
        return store.market_proxy_not_configured(f"dragon-list:{date}")

    @app.get("/v1/market/fundamentals", response_model=MarketProxyResponse)
    def fundamentals(codes: str, user_id: str = Depends(current_user)) -> MarketProxyResponse:
        store.require_active_vip(user_id)
        return store.market_proxy_not_configured(f"fundamentals:{codes}")

    return app


def now_millis() -> int:
    return int(time.time() * 1000)


def hash_password(password: str, salt: str) -> str:
    return hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt.encode("utf-8"),
        120_000,
    ).hex()


def verify_callback_signature(request: PaymentCallbackRequest) -> None:
    secret = os.getenv("QUANTTRADING_PAYMENT_CALLBACK_SECRET", "")
    # Fail closed: an unsigned payment callback must never grant entitlements.
    # The unsigned path is reachable only when an operator explicitly opts in
    # with QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE=0 (local/dev use only); any
    # other value, including unset, requires a verified signature.
    require_signature = os.getenv("QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE", "1") != "0"
    if not secret:
        if require_signature:
            raise HTTPException(status_code=401, detail="payment callback secret is not configured")
        return
    if request.timestamp is None:
        raise HTTPException(status_code=401, detail="payment callback timestamp is required")
    skew = abs(now_millis() - request.timestamp)
    if skew > CALLBACK_TIMESTAMP_WINDOW_MILLIS:
        raise HTTPException(status_code=401, detail="payment callback timestamp outside allowed window")
    message = (
        f"{request.timestamp}:"
        f"{request.orderId}:"
        f"{request.providerTransactionId}:"
        f"{request.amountCents}:"
        f"{request.eventType}"
    )
    expected = hmac.new(secret.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()
    if not request.signature or not hmac.compare_digest(expected, request.signature):
        raise HTTPException(status_code=401, detail="invalid payment callback signature")


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


def reduce_entitlement(
    tier: str,
    days: int,
    stock_expiry: int,
    quant_expiry: int,
) -> tuple[int, int]:
    duration = days * DAY_MILLIS

    def reduce_one(current_expiry: int) -> int:
        reduced = current_expiry - duration
        return reduced if reduced > now_millis() else 0

    if tier == "STOCK":
        return reduce_one(stock_expiry), quant_expiry
    if tier == "QUANT":
        return stock_expiry, reduce_one(quant_expiry)
    if tier == "FULL":
        return reduce_one(stock_expiry), reduce_one(quant_expiry)
    raise HTTPException(status_code=400, detail="unknown tier")


app = create_app()
