package com.tianxian.quant.ui.auth

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tianxian.quant.R
import com.tianxian.quant.data.LocalStateRepository
import com.tianxian.quant.data.UserStateEntity
import com.tianxian.quant.databinding.ActivityAuthBinding
import com.tianxian.quant.util.NotificationHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private var pendingNotificationAction = NotificationAction.ENABLE_DAILY
    private val finishOnAuth: Boolean
        get() = intent.getBooleanExtra(EXTRA_FINISH_ON_AUTH, false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lifecycleScope.launch {
            if (!granted) {
                if (pendingNotificationAction == NotificationAction.ENABLE_DAILY) {
                    LocalStateRepository.setNotificationsEnabled(false)
                    NotificationHelper.cancelDailyReminder(this@AuthActivity)
                }
                renderState(LocalStateRepository.getUserState())
                Toast.makeText(this@AuthActivity, "未获得通知权限", Toast.LENGTH_SHORT).show()
                return@launch
            }

            when (pendingNotificationAction) {
                NotificationAction.ENABLE_DAILY -> {
                    LocalStateRepository.setNotificationsEnabled(true)
                    NotificationHelper.scheduleDailyReminder(this@AuthActivity)
                    NotificationHelper.showResearchReminder(this@AuthActivity)
                    Toast.makeText(this@AuthActivity, "每日研究提醒已开启", Toast.LENGTH_SHORT).show()
                }
                NotificationAction.TEST_ONLY -> {
                    NotificationHelper.showResearchReminder(this@AuthActivity)
                    Toast.makeText(this@AuthActivity, "已发送一条本地测试提醒", Toast.LENGTH_SHORT).show()
                }
            }
            renderState(LocalStateRepository.getUserState())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.auth_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        NotificationHelper.ensureChannel(this)
        setupActions()
        refreshState()
    }

    private fun setupActions() {
        binding.btnRegister.setOnClickListener {
            val displayName = binding.etDisplayName.text?.toString()?.trim().orEmpty()
            val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString()?.trim().orEmpty()
            if (!validateInput(phone, password)) return@setOnClickListener
            lifecycleScope.launch {
                val state = LocalStateRepository.register(displayName, phone, password)
                completeAuth(state, "注册并登录成功")
            }
        }

        binding.btnLogin.setOnClickListener {
            val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString()?.trim().orEmpty()
            if (!validateInput(phone, password)) return@setOnClickListener
            lifecycleScope.launch {
                val success = LocalStateRepository.login(phone, password)
                if (success) {
                    completeAuth(LocalStateRepository.getUserState(), "登录成功")
                } else {
                    Toast.makeText(this@AuthActivity, "账号或密码不匹配", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnLogout.setOnClickListener {
            lifecycleScope.launch {
                LocalStateRepository.logout()
                renderState(LocalStateRepository.getUserState())
                Toast.makeText(this@AuthActivity, "已退出登录", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnNotify.setOnClickListener {
            toggleDailyReminder()
        }

        binding.btnNotifyTest.setOnClickListener {
            sendTestNotification()
        }
    }

    private fun completeAuth(state: UserStateEntity, message: String) {
        renderState(state)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (finishOnAuth) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun validateInput(phone: String, password: String): Boolean {
        if (!phone.matches(Regex("\\d{11}"))) {
            Toast.makeText(this, "请输入 11 位手机号", Toast.LENGTH_SHORT).show()
            return false
        }
        if (password.length < 6) {
            Toast.makeText(this, "密码至少 6 位", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun refreshState() {
        lifecycleScope.launch {
            renderState(LocalStateRepository.getUserState())
        }
    }

    private fun renderState(state: UserStateEntity) {
        val loginText = if (state.isLoggedIn) {
            getString(R.string.auth_status_logged_in)
        } else {
            getString(R.string.auth_status_logged_out)
        }
        val now = System.currentTimeMillis()
        val stockVipText = if (state.stockVipExpireTime > now) {
            getString(R.string.auth_stock_vip_active, formatDate(state.stockVipExpireTime))
        } else {
            getString(R.string.auth_stock_vip_inactive)
        }
        val quantVipText = if (state.quantVipExpireTime > now) {
            getString(R.string.auth_quant_vip_active, formatDate(state.quantVipExpireTime))
        } else {
            getString(R.string.auth_quant_vip_inactive)
        }
        val phoneText = state.phone ?: getString(R.string.auth_phone_unbound)
        val hasNotificationPermission = NotificationHelper.canPostNotifications(this)
        val notifyText = when {
            state.notificationsEnabled && hasNotificationPermission -> {
                getString(
                    R.string.auth_notification_enabled_next,
                    NotificationHelper.nextReminderTimeLabel()
                )
            }
            state.notificationsEnabled -> {
                getString(R.string.auth_notification_enabled_permission_missing)
            }
            else -> getString(R.string.auth_notification_disabled)
        }
        binding.tvAccountStatus.text = getString(
            R.string.auth_account_status_format,
            loginText,
            stockVipText,
            quantVipText,
            phoneText,
            notifyText
        )
        binding.btnNotify.text = when {
            state.notificationsEnabled && !hasNotificationPermission -> {
                getString(R.string.auth_reauthorize_notification)
            }
            state.notificationsEnabled -> getString(R.string.auth_disable_daily_reminder)
            else -> getString(R.string.auth_enable_daily_reminder)
        }
        binding.etDisplayName.setText(state.displayName.takeIf { it != "本机用户" }.orEmpty())
        binding.etPhone.setText(state.phone.orEmpty())
    }

    private fun formatDate(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun sendTestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationHelper.canPostNotifications(this)
        ) {
            pendingNotificationAction = NotificationAction.TEST_ONLY
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        lifecycleScope.launch {
            NotificationHelper.showResearchReminder(this@AuthActivity)
            renderState(LocalStateRepository.getUserState())
            Toast.makeText(this@AuthActivity, "已发送一条本地测试提醒", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleDailyReminder() {
        lifecycleScope.launch {
            val current = LocalStateRepository.getUserState()
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !NotificationHelper.canPostNotifications(this@AuthActivity)
            if (current.notificationsEnabled && needsPermission) {
                pendingNotificationAction = NotificationAction.ENABLE_DAILY
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@launch
            }
            if (current.notificationsEnabled) {
                LocalStateRepository.setNotificationsEnabled(false)
                NotificationHelper.cancelDailyReminder(this@AuthActivity)
                renderState(LocalStateRepository.getUserState())
                Toast.makeText(this@AuthActivity, "每日研究提醒已关闭", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (needsPermission) {
                pendingNotificationAction = NotificationAction.ENABLE_DAILY
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@launch
            }
            LocalStateRepository.setNotificationsEnabled(true)
            NotificationHelper.scheduleDailyReminder(this@AuthActivity)
            renderState(LocalStateRepository.getUserState())
            Toast.makeText(this@AuthActivity, "每日研究提醒已开启", Toast.LENGTH_SHORT).show()
        }
    }

    private enum class NotificationAction {
        ENABLE_DAILY,
        TEST_ONLY
    }

    companion object {
        private const val EXTRA_FINISH_ON_AUTH = "finish_on_auth"

        fun createIntent(context: Context, finishOnAuth: Boolean = false): Intent {
            return Intent(context, AuthActivity::class.java)
                .putExtra(EXTRA_FINISH_ON_AUTH, finishOnAuth)
        }
    }
}
