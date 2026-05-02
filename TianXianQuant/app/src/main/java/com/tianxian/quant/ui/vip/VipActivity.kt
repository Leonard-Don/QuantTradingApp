package com.tianxian.quant.ui.vip

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tianxian.quant.BuildConfig
import com.tianxian.quant.R
import com.tianxian.quant.data.LocalStateRepository
import com.tianxian.quant.data.SubscriptionOrderEntity
import com.tianxian.quant.databinding.ActivityVipBinding
import com.tianxian.quant.model.VipTier
import com.tianxian.quant.payment.PaymentChannel
import com.tianxian.quant.payment.PaymentGateway
import com.tianxian.quant.payment.PaymentResult
import com.tianxian.quant.payment.SubscriptionOrder
import com.tianxian.quant.ui.auth.AuthActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VipActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVipBinding
    private var selectedNormalPrice: PriceOption = PriceOption.MONTHLY
    private var pendingSubscription: PendingSubscription? = null
    private val finishOnSuccess: Boolean
        get() = intent.getBooleanExtra(EXTRA_FINISH_ON_SUCCESS, false)

    private val subscriptionAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val request = pendingSubscription
        pendingSubscription = null
        refreshAccountCard()
        if (result.resultCode != Activity.RESULT_OK || request == null) return@registerForActivityResult
        lifecycleScope.launch {
            if (LocalStateRepository.isLoggedIn()) {
                showPaymentChannelDialog(request.tier, request.price, request.days)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVipBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.vip_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupNormalPriceOptions()
        setupAccountCard()
        setupVipCards()
    }

    override fun onResume() {
        super.onResume()
        refreshAccountCard()
    }

    private fun setupNormalPriceOptions() {
        val optionBg = ContextCompat.getDrawable(this, R.drawable.bg_price_option)
        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_price_option_selected)

        val monthly = binding.root.findViewById<TextView>(R.id.normalMonthly)
        val quarterly = binding.root.findViewById<TextView>(R.id.normalQuarterly)
        val yearly = binding.root.findViewById<TextView>(R.id.normalYearly)

        fun updateSelection(selected: TextView) {
            listOf(monthly, quarterly, yearly).forEach { tv ->
                tv.background = if (tv == selected) selectedBg else optionBg
            }
        }

        monthly?.setOnClickListener {
            selectedNormalPrice = PriceOption.MONTHLY
            updateSelection(monthly)
        }
        quarterly?.setOnClickListener {
            selectedNormalPrice = PriceOption.QUARTERLY
            updateSelection(quarterly)
        }
        yearly?.setOnClickListener {
            selectedNormalPrice = PriceOption.YEARLY
            updateSelection(yearly)
        }

        updateSelection(monthly)
    }

    private fun setupAccountCard() {
        binding.btnAccount.setOnClickListener {
            startActivity(AuthActivity.createIntent(this))
        }
        binding.btnRefreshOrders.setOnClickListener {
            lifecycleScope.launch {
                renderSubscriptionOrders(LocalStateRepository.refreshSubscriptionOrders())
                Toast.makeText(this@VipActivity, "订阅订单状态已刷新", Toast.LENGTH_SHORT).show()
            }
        }
        refreshAccountCard()
    }

    private fun refreshAccountCard() {
        lifecycleScope.launch {
            val state = LocalStateRepository.refreshBackendEntitlements()
            val orders = LocalStateRepository.refreshSubscriptionOrders()
            val now = System.currentTimeMillis()
            val loginText = if (state.isLoggedIn) {
                "${state.displayName} · 已登录"
            } else {
                "未登录 · 订阅前请先登录"
            }
            binding.tvAccountSummary.text = loginText
            binding.tvEntitlementDetail.text = buildString {
                append("选股 VIP：${formatVipStatus(state.stockVipExpireTime, state.backendGraceUntil, now)}\n")
                append("量化 VIP：${formatVipStatus(state.quantVipExpireTime, state.backendGraceUntil, now)}\n")
                append("同步状态：${state.backendSyncStatus}")
            }
            binding.btnAccount.text = if (state.isLoggedIn) "账号" else "登录/注册"
            renderSubscriptionOrders(orders)
        }
    }

    private fun setupVipCards() {
        binding.cardNormal.setOnClickListener {
            val priceText = when (selectedNormalPrice) {
                PriceOption.MONTHLY -> "68元/月"
                PriceOption.QUARTERLY -> "168元/季"
                PriceOption.YEARLY -> "588元/年"
            }
            val days = when (selectedNormalPrice) {
                PriceOption.MONTHLY -> 31
                PriceOption.QUARTERLY -> 93
                PriceOption.YEARLY -> 366
            }
            showSubscriptionDialog(VipTier.STOCK, priceText, days)
        }

        binding.cardSenior.setOnClickListener {
            showSubscriptionDialog(VipTier.QUANT, "168元/季", 93)
        }
    }

    private fun showSubscriptionDialog(tier: VipTier, price: String, days: Int) {
        lifecycleScope.launch {
            if (!LocalStateRepository.isLoggedIn()) {
                pendingSubscription = PendingSubscription(tier, price, days)
                android.app.AlertDialog.Builder(this@VipActivity)
                    .setTitle("请先登录")
                    .setMessage("订阅 VIP 前需要先完成本机登录/注册，用于保存会员状态。")
                    .setPositiveButton("登录/注册") { _, _ ->
                        subscriptionAuthLauncher.launch(
                            AuthActivity.createIntent(this@VipActivity, finishOnAuth = true)
                        )
                    }
                    .setOnCancelListener {
                        pendingSubscription = null
                    }
                    .setNegativeButton("取消") { _, _ ->
                        pendingSubscription = null
                    }
                    .show()
                return@launch
            }
            showPaymentChannelDialog(tier, price, days)
        }
    }

    private fun showPaymentChannelDialog(tier: VipTier, price: String, days: Int) {
        val paymentNote = if (
            BuildConfig.ENABLE_BACKEND_ACCOUNT_SYNC &&
            BuildConfig.ALLOW_LOCAL_PAYMENT_SIMULATION &&
            BuildConfig.REQUIRE_BACKEND_PAYMENT_SYNC
        ) {
            "当前为后端联调验收构建，会创建服务端沙盒订单并同步权益；服务端不可用时不会回退本机演示。"
        } else if (BuildConfig.ENABLE_BACKEND_ACCOUNT_SYNC && BuildConfig.ALLOW_LOCAL_PAYMENT_SIMULATION) {
            "当前为后端联调验收构建，会优先创建服务端沙盒订单并同步权益；服务端不可用时可回退本机演示。"
        } else if (BuildConfig.ALLOW_LOCAL_PAYMENT_SIMULATION) {
            "当前为验收构建，会使用本地模拟支付完成订阅。正式扣款仍需要配置微信/支付宝商户参数和服务端订单校验。"
        } else {
            "当前正式构建不会直接开通 VIP。微信/支付宝扣款需要商户参数、签名和服务端订单校验接入完成后启用。"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("确认订阅")
            .setMessage("您即将开通或续期 ${tier.displayName}\n价格：$price\n有效期：$days 天\n\n请选择支付方式。\n\n$paymentNote")
            .setPositiveButton(PaymentChannel.WECHAT.label) { _, _ ->
                startPayment(
                    SubscriptionOrder(
                        tier = tier,
                        priceText = price,
                        durationDays = days,
                        channel = PaymentChannel.WECHAT
                    )
                )
            }
            .setNeutralButton(PaymentChannel.ALIPAY.label) { _, _ ->
                startPayment(
                    SubscriptionOrder(
                        tier = tier,
                        priceText = price,
                        durationDays = days,
                        channel = PaymentChannel.ALIPAY
                    )
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startPayment(order: SubscriptionOrder) {
        lifecycleScope.launch {
            when (val result = PaymentGateway.startSubscription(order)) {
                PaymentResult.DebugPaid -> activateVipAfterPayment(order)
                is PaymentResult.NotConfigured -> {
                    android.app.AlertDialog.Builder(this@VipActivity)
                        .setTitle("支付通道未配置")
                        .setMessage("${result.channel.label} 需要商户 AppId、签名和服务端订单接口。当前正式构建不会直接开通 VIP。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }
    }

    private suspend fun activateVipAfterPayment(order: SubscriptionOrder) {
        val state = LocalStateRepository.activateVip(order.tier, order.durationDays, order.channel)
        val expireTime = when (order.tier) {
            VipTier.STOCK -> state.stockVipExpireTime
            VipTier.QUANT -> state.quantVipExpireTime
            VipTier.FULL -> maxOf(state.stockVipExpireTime, state.quantVipExpireTime)
        }
        val now = System.currentTimeMillis()
        val isActive = expireTime > now || (expireTime > 0L && state.backendGraceUntil > now)
        val dateText = if (expireTime > 0L) formatDate(expireTime) else "未生效"
        refreshAccountCard()
        android.app.AlertDialog.Builder(this@VipActivity)
            .setTitle(if (isActive) "订阅已生效" else "订阅未完成")
            .setMessage(
                if (isActive) {
                    "${order.channel.label} 验收支付完成。\n" +
                        "${order.tier.displayName} 已开通/续期，有效期至：$dateText\n" +
                        "同步状态：${state.backendSyncStatus}"
                } else {
                    "${order.channel.label} 尚未完成权益开通。\n" +
                        "请确认后端订单、支付回调和权益接口可用后重试。\n" +
                        "同步状态：${state.backendSyncStatus}"
                }
            )
            .setPositiveButton(if (finishOnSuccess) "返回使用" else "知道了") { _, _ ->
                if (finishOnSuccess && isActive) {
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
            .show()
    }

    private fun formatDate(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun renderSubscriptionOrders(orders: List<SubscriptionOrderEntity>) {
        if (orders.isEmpty()) {
            binding.tvSubscriptionOrderHistory.text = getString(R.string.vip_order_empty)
            return
        }
        binding.tvSubscriptionOrderHistory.text = orders.take(5).joinToString("\n\n") { order ->
            buildString {
                append(statusLabel(order.status))
                append(" · ")
                append(tierLabel(order.tier))
                append(" · ")
                append(channelLabel(order.channel))
                append("\n")
                append(formatAmount(order.amountCents, order.currency))
                append(" / ")
                append(order.durationDays)
                append("天")
                append(" · 下单 ")
                append(formatDate(order.createdAt))
                order.paidAt?.let {
                    append(" · 支付 ")
                    append(formatDate(it))
                }
                append("\n")
                append(order.note.ifBlank { "状态来源：${order.source}" })
            }
        }
    }

    private fun formatAmount(amountCents: Int, currency: String): String {
        val amount = amountCents / 100.0
        val prefix = if (currency == "CNY") "¥" else "$currency "
        return "$prefix${String.format(Locale.CHINA, "%.2f", amount)}"
    }

    private fun statusLabel(status: String): String {
        return when (status) {
            "PAID" -> "已支付"
            "PENDING" -> "待确认"
            "REFUNDED" -> "已退款"
            "CANCELLED" -> "已取消"
            "MANUAL_REVIEW" -> "人工复核"
            else -> status
        }
    }

    private fun tierLabel(tier: String): String {
        return when (tier) {
            VipTier.STOCK.name -> VipTier.STOCK.displayName
            VipTier.QUANT.name -> VipTier.QUANT.displayName
            VipTier.FULL.name -> VipTier.FULL.displayName
            else -> tier
        }
    }

    private fun channelLabel(channel: String): String {
        return when (channel) {
            PaymentChannel.WECHAT.name -> PaymentChannel.WECHAT.label
            PaymentChannel.ALIPAY.name -> PaymentChannel.ALIPAY.label
            else -> channel
        }
    }

    private fun formatVipStatus(expireTime: Long, graceUntil: Long, now: Long): String {
        if (expireTime <= now && expireTime > 0L && graceUntil > now) {
            return "宽限期至 ${formatDate(graceUntil)}"
        }
        if (expireTime <= now) return "未开通"
        val days = ((expireTime - now) + DAY_MILLIS - 1) / DAY_MILLIS
        return "已开通，剩余 ${days} 天（至 ${formatDate(expireTime)}）"
    }

    enum class PriceOption {
        MONTHLY, QUARTERLY, YEARLY
    }

    private data class PendingSubscription(
        val tier: VipTier,
        val price: String,
        val days: Int
    )

    companion object {
        private const val EXTRA_FINISH_ON_SUCCESS = "finish_on_success"
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        fun createIntent(context: Context, finishOnSuccess: Boolean = false): Intent {
            return Intent(context, VipActivity::class.java)
                .putExtra(EXTRA_FINISH_ON_SUCCESS, finishOnSuccess)
        }
    }
}
