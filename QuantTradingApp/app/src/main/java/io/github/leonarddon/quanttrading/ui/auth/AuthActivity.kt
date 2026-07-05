package io.github.leonarddon.quanttrading.ui.auth

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.leonarddon.quanttrading.BuildConfig
import io.github.leonarddon.quanttrading.R
import io.github.leonarddon.quanttrading.data.LocalStateRepository
import io.github.leonarddon.quanttrading.data.UserStateEntity
import io.github.leonarddon.quanttrading.databinding.ActivityAuthBinding
import io.github.leonarddon.quanttrading.util.NotificationHelper
import kotlinx.coroutines.launch

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

        binding.btnDeleteAccount.setOnClickListener {
            confirmDeleteAccount()
        }

        binding.btnNotify.setOnClickListener {
            toggleDailyReminder()
        }

        binding.btnNotifyTest.setOnClickListener {
            sendTestNotification()
        }

        binding.btnPrivacyPolicy.setOnClickListener {
            showExternalInfoDialog(
                title = getString(R.string.auth_privacy_policy),
                message = getString(R.string.auth_privacy_policy_message),
                externalUrl = BuildConfig.PRIVACY_POLICY_URL
            )
        }

        binding.btnTermsOfService.setOnClickListener {
            showExternalInfoDialog(
                title = getString(R.string.auth_terms_of_service),
                message = getString(R.string.auth_terms_of_service_message),
                externalUrl = BuildConfig.TERMS_OF_SERVICE_URL
            )
        }

        binding.btnSupportContact.setOnClickListener {
            showInfoDialog(
                title = getString(R.string.auth_support_contact),
                message = buildSupportMessage()
            )
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

    private fun refreshState(showToast: Boolean = false) {
        lifecycleScope.launch {
            renderState(LocalStateRepository.getUserState())
            if (showToast) {
                Toast.makeText(
                    this@AuthActivity,
                    getString(R.string.auth_account_refreshed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showInfoDialog(title: String, message: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun showExternalInfoDialog(title: String, message: String, externalUrl: String) {
        val trimmedUrl = externalUrl.trim()
        if (trimmedUrl.isBlank()) {
            showInfoDialog(title, message)
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$message\n\n链接：$trimmedUrl")
            .setPositiveButton(getString(R.string.auth_open_external_link)) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trimmedUrl)))
            }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun buildSupportMessage(): String {
        val supportEmail = BuildConfig.SUPPORT_EMAIL.trim()
        val dataDisclaimerUrl = BuildConfig.DATA_DISCLAIMER_URL.trim()
        return buildString {
            append(getString(R.string.auth_support_contact_message))
            if (supportEmail.isNotBlank()) {
                append("\n\n客服邮箱：").append(supportEmail)
            }
            if (dataDisclaimerUrl.isNotBlank()) {
                append("\n\n数据源与免责声明：").append(dataDisclaimerUrl)
            }
        }
    }

    private fun confirmDeleteAccount() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.auth_delete_account))
            .setMessage(getString(R.string.auth_delete_account_confirm_message))
            .setPositiveButton(getString(R.string.auth_delete_account_confirm)) { _, _ ->
                lifecycleScope.launch {
                    val result = LocalStateRepository.deleteAccount()
                    if (result.success) {
                        NotificationHelper.cancelDailyReminder(this@AuthActivity)
                    }
                    renderState(LocalStateRepository.getUserState())
                    Toast.makeText(this@AuthActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun renderState(state: UserStateEntity) {
        val loginText = if (state.isLoggedIn) {
            getString(R.string.auth_status_logged_in)
        } else {
            getString(R.string.auth_status_logged_out)
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
            phoneText,
            notifyText,
            state.accountStatus
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
