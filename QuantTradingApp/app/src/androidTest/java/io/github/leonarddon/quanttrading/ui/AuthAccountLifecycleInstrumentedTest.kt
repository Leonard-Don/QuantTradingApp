package io.github.leonarddon.quanttrading.ui

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leonarddon.quanttrading.R
import io.github.leonarddon.quanttrading.data.LocalStateRepository
import io.github.leonarddon.quanttrading.ui.auth.AuthActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthAccountLifecycleInstrumentedTest {
    private lateinit var scenario: ActivityScenario<AuthActivity>

    @Before
    fun setUp() {
        seedLoggedInAccount()
        val context = ApplicationProvider.getApplicationContext<Context>()
        scenario = ActivityScenario.launch(AuthActivity.createIntent(context))
        waitForAccountStatusText("已登录")
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
    }

    @Test
    fun accountDeletionIsReachableFromAuthScreenAndClearsLocalAccount() {
        onView(withId(R.id.btnDeleteAccount)).perform(scrollTo(), click())
        onView(withText(R.string.auth_delete_account_confirm)).check(matches(isDisplayed()))
        onView(withText(R.string.auth_delete_account_confirm)).perform(click())

        waitForAccountStatusText("未登录")

        val state = runBlocking { LocalStateRepository.getUserState() }
        assertFalse("Account should be logged out after deletion", state.isLoggedIn)
        assertNull("Phone should be cleared after deletion", state.phone)
    }

    private fun seedLoggedInAccount() = runBlocking {
        LocalStateRepository.deleteAccount()
        LocalStateRepository.register("账号生命周期巡检", "13900000001", "pass1234")
    }

    private fun waitForAccountStatusText(expected: String) {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        var lastText = ""
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                lastText = activity.findViewById<TextView>(R.id.tvAccountStatus).text.toString()
            }
            if (lastText.contains(expected)) return
            Thread.sleep(100)
        }
        error("Account status did not contain '$expected'. Last text: $lastText")
    }

    private companion object {
        const val UI_TIMEOUT_MS = 5_000L
    }
}
