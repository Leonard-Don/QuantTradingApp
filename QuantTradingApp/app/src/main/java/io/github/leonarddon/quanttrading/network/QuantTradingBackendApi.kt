package io.github.leonarddon.quanttrading.network

import io.github.leonarddon.quanttrading.BuildConfig
import io.github.leonarddon.quanttrading.MyApp
import io.github.leonarddon.quanttrading.model.VipTier
import io.github.leonarddon.quanttrading.payment.PaymentChannel
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

data class BackendRegisterRequest(
    val displayName: String,
    val phone: String,
    val password: String,
    val deviceId: String
)

data class BackendLoginRequest(
    val phone: String,
    val password: String,
    val deviceId: String
)

data class BackendRefreshRequest(
    val refreshToken: String,
    val deviceId: String
)

data class BackendAuthResponse(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

data class BackendEntitlementResponse(
    val userId: String,
    val serverTime: Long,
    val stockVipExpireTime: Long,
    val quantVipExpireTime: Long,
    val graceUntil: Long,
    val source: String
)

data class BackendOrderRequest(
    val tier: String,
    val durationDays: Int,
    val channel: String,
    val clientOrderId: String,
    val deviceId: String
)

data class BackendPaymentPayload(
    val sandbox: Boolean,
    val orderId: String,
    val channel: String,
    val note: String
)

data class BackendOrderResponse(
    val orderId: String,
    val tier: String,
    val durationDays: Int,
    val amountCents: Int,
    val currency: String,
    val channel: String,
    val status: String,
    val paymentPayload: BackendPaymentPayload
)

data class BackendOrderEntitlement(
    val stockVipExpireTime: Long,
    val quantVipExpireTime: Long
)

data class BackendOrderStatusResponse(
    val orderId: String,
    val tier: String,
    val durationDays: Int,
    val amountCents: Int,
    val currency: String,
    val channel: String,
    val status: String,
    val createdAt: Long,
    val paidAt: Long?,
    val entitlement: BackendOrderEntitlement?
)

/**
 * Payment callback contract.
 *
 * In production, the WeChat / Alipay payment provider posts this callback
 * server-to-server to the QuantTradingApp backend with [timestamp] and [signature]
 * computed against [QUANTTRADING_PAYMENT_CALLBACK_SECRET]. The Android client never
 * issues this callback in Release builds.
 *
 * The Debug-only sandbox path included with [QuantTradingBackendRepository] sends
 * [timestamp] so the backend can enforce its ±5min replay window even when no
 * signature is configured.
 */
data class BackendPaymentCallbackRequest(
    val orderId: String,
    val providerTransactionId: String,
    val amountCents: Int,
    val sandboxApproved: Boolean = true,
    val eventType: String = "PAID",
    val timestamp: Long? = null,
    val signature: String? = null
)

data class BackendPaymentCallbackResponse(
    val orderId: String,
    val status: String,
    val stockVipExpireTime: Long,
    val quantVipExpireTime: Long
)

data class BackendAccountSync(
    val enabled: Boolean,
    val success: Boolean,
    val message: String,
    val auth: BackendAuthResponse? = null,
    val entitlement: BackendEntitlementResponse? = null,
    val order: BackendOrderStatusResponse? = null
) {
    companion object {
        fun disabled(): BackendAccountSync = BackendAccountSync(
            enabled = false,
            success = false,
            message = "服务端同步未启用，当前使用本机演示账号"
        )

        fun failure(message: String, order: BackendOrderStatusResponse? = null): BackendAccountSync = BackendAccountSync(
            enabled = true,
            success = false,
            message = message,
            order = order
        )

        fun success(
            auth: BackendAuthResponse?,
            entitlement: BackendEntitlementResponse?,
            message: String,
            order: BackendOrderStatusResponse? = null
        ): BackendAccountSync = BackendAccountSync(
            enabled = true,
            success = true,
            message = message,
            auth = auth,
            entitlement = entitlement,
            order = order
        )
    }
}

data class BackendOrdersSync(
    val enabled: Boolean,
    val success: Boolean,
    val message: String,
    val orders: List<BackendOrderStatusResponse> = emptyList()
) {
    companion object {
        fun disabled(): BackendOrdersSync = BackendOrdersSync(
            enabled = false,
            success = false,
            message = "服务端同步未启用，当前展示本机订单记录"
        )

        fun failure(message: String): BackendOrdersSync = BackendOrdersSync(
            enabled = true,
            success = false,
            message = message
        )

        fun success(orders: List<BackendOrderStatusResponse>): BackendOrdersSync = BackendOrdersSync(
            enabled = true,
            success = true,
            message = "服务端订单状态已同步",
            orders = orders
        )
    }
}

interface QuantTradingBackendApi {
    @POST("v1/auth/register")
    suspend fun register(@Body request: BackendRegisterRequest): BackendAuthResponse

    @POST("v1/auth/login")
    suspend fun login(@Body request: BackendLoginRequest): BackendAuthResponse

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body request: BackendRefreshRequest): BackendAuthResponse

    @GET("v1/me/entitlements")
    suspend fun entitlements(
        @Header("Authorization") authorization: String
    ): BackendEntitlementResponse

    @DELETE("v1/me")
    suspend fun deleteMe(
        @Header("Authorization") authorization: String
    ): Map<String, String>

    @POST("v1/orders")
    suspend fun createOrder(
        @Header("Authorization") authorization: String,
        @Body request: BackendOrderRequest
    ): BackendOrderResponse

    @GET("v1/orders/{orderId}")
    suspend fun orderStatus(
        @Header("Authorization") authorization: String,
        @Path("orderId") orderId: String
    ): BackendOrderStatusResponse

    @GET("v1/me/orders")
    suspend fun orders(
        @Header("Authorization") authorization: String
    ): List<BackendOrderStatusResponse>

    @POST("v1/payment/callbacks/{channel}")
    suspend fun paymentCallback(
        @Path("channel") channel: String,
        @Body request: BackendPaymentCallbackRequest
    ): BackendPaymentCallbackResponse
}

object QuantTradingBackendRepository {
    val isEnabled: Boolean
        get() = BuildConfig.ENABLE_BACKEND_ACCOUNT_SYNC

    private val api: QuantTradingBackendApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(BuildConfig.APP_API_BASE_URL.withTrailingSlash())
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuantTradingBackendApi::class.java)
    }

    suspend fun register(displayName: String, phone: String, password: String): BackendAccountSync {
        if (!isEnabled) return BackendAccountSync.disabled()
        return runCatching {
            val auth = api.register(
                BackendRegisterRequest(
                    displayName = displayName.ifBlank { "本机用户" },
                    phone = phone,
                    password = password,
                    deviceId = deviceId()
                )
            )
            BackendAccountSync.success(auth, loadEntitlements(auth.accessToken), "服务端账号已同步")
        }.getOrElse {
            BackendAccountSync.failure("服务端注册失败：${it.shortMessage()}")
        }
    }

    suspend fun login(phone: String, password: String): BackendAccountSync {
        if (!isEnabled) return BackendAccountSync.disabled()
        return runCatching {
            val auth = api.login(
                BackendLoginRequest(
                    phone = phone,
                    password = password,
                    deviceId = deviceId()
                )
            )
            BackendAccountSync.success(auth, loadEntitlements(auth.accessToken), "服务端登录已同步")
        }.getOrElse {
            BackendAccountSync.failure("服务端登录失败：${it.shortMessage()}")
        }
    }

    suspend fun refreshSession(refreshToken: String?): BackendAccountSync {
        if (!isEnabled) return BackendAccountSync.disabled()
        if (refreshToken.isNullOrBlank()) {
            return BackendAccountSync.failure("缺少服务端刷新令牌，请重新登录")
        }
        return runCatching {
            val auth = api.refresh(
                BackendRefreshRequest(
                    refreshToken = refreshToken,
                    deviceId = deviceId()
                )
            )
            BackendAccountSync.success(auth, loadEntitlements(auth.accessToken), "服务端登录态已刷新")
        }.getOrElse {
            BackendAccountSync.failure("服务端登录态刷新失败：${it.shortMessage()}")
        }
    }

    suspend fun fetchEntitlements(accessToken: String?): BackendAccountSync {
        if (!isEnabled) return BackendAccountSync.disabled()
        if (accessToken.isNullOrBlank()) {
            return BackendAccountSync.failure("缺少服务端访问令牌，无法同步权益")
        }
        return runCatching {
            BackendAccountSync.success(
                auth = null,
                entitlement = loadEntitlements(accessToken),
                message = "服务端权益已同步"
            )
        }.getOrElse {
            BackendAccountSync.failure("服务端权益同步失败：${it.shortMessage()}")
        }
    }

    suspend fun activateSandboxSubscription(
        accessToken: String?,
        tier: VipTier,
        durationDays: Int,
        channel: PaymentChannel
    ): BackendAccountSync {
        if (!isEnabled) return BackendAccountSync.disabled()
        if (!BuildConfig.ALLOW_LOCAL_PAYMENT_SIMULATION) {
            return BackendAccountSync.failure("正式构建不允许使用沙盒回调开通权益")
        }
        if (accessToken.isNullOrBlank()) {
            return BackendAccountSync.failure("缺少服务端访问令牌，无法创建订阅订单")
        }
        return runCatching {
            val order = api.createOrder(
                bearer(accessToken),
                BackendOrderRequest(
                    tier = tier.name,
                    durationDays = durationDays,
                    channel = channel.name,
                    clientOrderId = "android-${UUID.randomUUID()}",
                    deviceId = deviceId()
                )
            )
            val pendingOrder = order.toStatusResponse()
            runCatching {
                api.paymentCallback(
                    channel.name,
                    BackendPaymentCallbackRequest(
                        orderId = order.orderId,
                        providerTransactionId = "sandbox-${UUID.randomUUID()}",
                        amountCents = order.amountCents,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }.getOrElse {
                return BackendAccountSync.failure(
                    "服务端订阅同步失败：${it.shortMessage()}",
                    order = pendingOrder
                )
            }
            val currentOrder = api.orderStatus(bearer(accessToken), order.orderId)
            val entitlement = loadEntitlements(accessToken)
            BackendAccountSync.success(
                auth = null,
                entitlement = entitlement,
                message = "服务端沙盒订单已完成，权益已同步",
                order = currentOrder
            )
        }.getOrElse {
            BackendAccountSync.failure("服务端订阅同步失败：${it.shortMessage()}")
        }
    }

    suspend fun fetchOrders(accessToken: String?): BackendOrdersSync {
        if (!isEnabled) return BackendOrdersSync.disabled()
        if (accessToken.isNullOrBlank()) {
            return BackendOrdersSync.failure("缺少服务端访问令牌，无法同步订单")
        }
        return runCatching {
            BackendOrdersSync.success(api.orders(bearer(accessToken)))
        }.getOrElse {
            BackendOrdersSync.failure("服务端订单同步失败：${it.shortMessage()}")
        }
    }

    suspend fun deleteAccount(accessToken: String?): BackendAccountSync {
        if (!isEnabled) return BackendAccountSync.disabled()
        if (accessToken.isNullOrBlank()) {
            return BackendAccountSync.failure("缺少服务端访问令牌，无法删除服务端账号")
        }
        return runCatching {
            api.deleteMe(bearer(accessToken))
            BackendAccountSync.success(
                auth = null,
                entitlement = null,
                message = "服务端账号已删除"
            )
        }.getOrElse {
            BackendAccountSync.failure("服务端账号删除失败：${it.shortMessage()}")
        }
    }

    private suspend fun loadEntitlements(accessToken: String): BackendEntitlementResponse {
        return api.entitlements(bearer(accessToken))
    }

    private fun bearer(accessToken: String): String = "Bearer $accessToken"

    private fun BackendOrderResponse.toStatusResponse(): BackendOrderStatusResponse {
        return BackendOrderStatusResponse(
            orderId = orderId,
            tier = tier,
            durationDays = durationDays,
            amountCents = amountCents,
            currency = currency,
            channel = channel,
            status = status,
            createdAt = System.currentTimeMillis(),
            paidAt = null,
            entitlement = null
        )
    }

    private fun deviceId(): String {
        val preferences = MyApp.instance.getSharedPreferences("quanttrading_backend", 0)
        val existing = preferences.getString(KEY_INSTALL_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android-${UUID.randomUUID()}"
        preferences.edit().putString(KEY_INSTALL_ID, created).apply()
        return created
    }

    private fun String.withTrailingSlash(): String = if (endsWith("/")) this else "$this/"

    private fun Throwable.shortMessage(): String {
        val raw = localizedMessage ?: javaClass.simpleName
        return raw.replace('\n', ' ').take(120)
    }

    private const val KEY_INSTALL_ID = "install_id"
}
