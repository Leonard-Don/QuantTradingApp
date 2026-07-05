package io.github.leonarddon.quanttrading.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.leonarddon.quanttrading.data.LocalStateRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureTourInstrumentedTest {
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        runBlocking { LocalStateRepository.deleteAccount() }
        launchMainActivity()
    }

    @Test
    fun coreFeatureTourCanUseAccountStockCommunityAndQuantFlows() {
        assertMainTabsVisible()

        registerLocalAccount()

        exerciseStockFlow()
        exerciseCommunityFlow()
        exerciseQuantFlow()
        exerciseAccountLogoutLogin()

        assertMainTabsVisible()
    }

    private fun launchMainActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
            ?: error("Launch intent not found for $PACKAGE_NAME")
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        waitForObject(By.pkg(PACKAGE_NAME).depth(0), LAUNCH_TIMEOUT_MS)
        device.waitForIdle()
    }

    private fun registerLocalAccount() {
        clickDesc("本机账号与设置")
        waitForText("账号状态")

        val suffix = (System.currentTimeMillis() % 100_000_000).toString().padStart(8, '0')
        val phone = "139$suffix"
        setTextByRes("etDisplayName", "UI自动化")
        setTextByRes("etPhone", phone)
        setTextByRes("etPassword", "pass1234")
        clickText("注册")
        waitForTextContains("已登录")

        scrollUntilText("隐私政策")
        clickText("隐私政策")
        waitForTextContains("Quant 交易台用于本机研究记录")
        clickAnyText("关闭")

        scrollUntilText("用户协议")
        clickText("用户协议")
        waitForTextContains("不构成投资建议")
        clickAnyText("关闭")

        device.pressBack()
        waitForDesc("选股")
    }

    private fun exerciseStockFlow() {
        clickDesc("选股")
        waitForRes("etSearch")
        waitForRes("tvStockName", LONG_TIMEOUT_MS)

        setTextByRes("etSearch", "600519")
        waitForRes("tvStockName", LONG_TIMEOUT_MS).click()
        waitForTextContains("指标强度")

        clickText("深度诊断")
        waitForTextContains("研究评分")
        clickAnyText("关闭")

        waitForRes("tvStockName", LONG_TIMEOUT_MS).click()
        waitForTextContains("指标强度")
        clickText("目标价提醒")
        waitForText("保存提醒")
        setTextByRes("etAlertTargetPrice", "200")
        clickText("保存提醒")
        waitForRes("etSearch")
    }

    private fun exerciseCommunityFlow() {
        clickDesc("社区")
        waitForDesc("发布帖子")
        clickDesc("发布帖子")
        waitForText("发布帖子")

        val title = "自动化复盘${System.currentTimeMillis() % 10_000}"
        setTextByRes("etTitle", title)
        setTextByRes("etContent", "自动化巡检发布的本机复盘记录，仅用于验证社区发布、详情和评论链路。")
        clickText("发布")
        waitForText(title, LONG_TIMEOUT_MS)

        clickText(title)
        waitForText("研究纪要")
        clickText("评论")
        waitForText("发表评论")
        setTextByRes("etComment", "已完成自动化评论巡检")
        clickText("发布")

        waitForText(title, LONG_TIMEOUT_MS)
        clickText(title)
        waitForText("研究纪要")
        clickText("研究纪要")
        waitForTextContains("纪要要点")
        clickAnyText("关闭")
    }

    private fun exerciseQuantFlow() {
        clickDesc("量化")
        waitForText("模型信号观察")

        waitForObject(By.textStartsWith("模型诊断"), LONG_TIMEOUT_MS)

        clickDesc("创建模型")
        waitForText("创建自定义策略")
        val strategyName = "UI巡检模型${System.currentTimeMillis() % 10_000}"
        setTextByRes("etStrategyName", strategyName)
        setTextByRes("etStrategyDesc", "自动化创建的量化研究模型")
        clickText("创建")
        waitForText(strategyName, LONG_TIMEOUT_MS)

        clickText(strategyName)
        waitForText("历史模拟")
        clickText("历史模拟")
        waitForText("历史模拟参数")
        clickText("取消")
    }

    private fun exerciseAccountLogoutLogin() {
        clickDesc("本机账号与设置")
        waitForText("账号状态")

        val phone = waitForRes("etPhone").text
        assertTrue("Expected saved phone from registration", phone.matches(Regex("\\d{11}")))

        scrollUntilText("退出登录")
        clickText("退出登录")
        scrollToTop()
        waitForTextContains("未登录")

        setTextByRes("etPhone", phone)
        setTextByRes("etPassword", "pass1234")
        clickText("登录")
        waitForTextContains("已登录")

        device.pressBack()
        waitForDesc("选股")
    }

    private fun assertMainTabsVisible() {
        waitForDesc("选股")
        waitForDesc("复盘")
        waitForDesc("社区")
        waitForDesc("量化")
        waitForDesc("本机账号与设置")
    }

    private fun waitForText(text: String, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 {
        return waitForObject(By.text(text), timeoutMs)
    }

    private fun waitForTextContains(text: String, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 {
        return waitForObject(By.textContains(text), timeoutMs)
    }

    private fun waitForDesc(desc: String, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 {
        return waitForObject(By.desc(desc), timeoutMs)
    }

    private fun waitForRes(id: String, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 {
        return waitForObject(By.res(PACKAGE_NAME, id), timeoutMs)
    }

    private fun waitForObject(selector: BySelector, timeoutMs: Long): UiObject2 {
        return device.wait(Until.findObject(selector), timeoutMs)
            ?: error("UI object not found for selector $selector")
    }

    private fun clickText(text: String) {
        waitForText(text).click()
        device.waitForIdle()
    }

    private fun clickAnyText(vararg texts: String) {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            texts.firstNotNullOfOrNull { text -> device.findObject(By.text(text)) }?.let {
                it.click()
                device.waitForIdle()
                return
            }
            Thread.sleep(250)
        }
        error("None of the expected texts were visible: ${texts.joinToString()}")
    }

    private fun pressBackUntilText(text: String, attempts: Int = 2) {
        repeat(attempts) {
            device.pressBack()
            if (device.wait(Until.hasObject(By.text(text)), 1_500L)) {
                return
            }
        }
        waitForText(text)
    }

    private fun clickDesc(desc: String) {
        waitForDesc(desc).click()
        device.waitForIdle()
    }

    private fun setTextByRes(id: String, value: String) {
        val field = waitForRes(id)
        field.click()
        field.text = value
        device.waitForIdle()
    }

    private fun scrollUntilText(text: String, maxSwipes: Int = 5): UiObject2 {
        repeat(maxSwipes) {
            device.findObject(By.text(text))?.let { return it }
            swipeUp()
            Thread.sleep(250)
        }
        return waitForText(text)
    }

    private fun scrollToTop() {
        repeat(4) {
            swipeDown()
            Thread.sleep(100)
        }
    }

    private fun swipeUp() {
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, (height * 0.78).toInt(), width / 2, (height * 0.28).toInt(), 24)
    }

    private fun swipeDown() {
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, (height * 0.28).toInt(), width / 2, (height * 0.78).toInt(), 24)
    }

    private companion object {
        const val PACKAGE_NAME = "io.github.leonarddon.quanttrading"
        const val UI_TIMEOUT_MS = 5_000L
        const val LONG_TIMEOUT_MS = 15_000L
        const val LAUNCH_TIMEOUT_MS = 10_000L
    }
}
