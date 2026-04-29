package com.tianxian.quant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.tianxian.quant.databinding.ActivityMainBinding
import com.tianxian.quant.util.NotificationHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NotificationHelper.ensureChannel(this)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)
        handleNavigationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        val targetDestination = when (intent?.getStringExtra(EXTRA_TARGET_TAB)) {
            TARGET_STOCK -> R.id.nav_stock
            TARGET_REVIEW -> R.id.nav_review
            TARGET_COMMUNITY -> R.id.nav_community
            TARGET_QUANT -> R.id.nav_quant
            else -> return
        }
        if (binding.bottomNav.selectedItemId != targetDestination) {
            binding.bottomNav.selectedItemId = targetDestination
        }
    }

    companion object {
        private const val EXTRA_TARGET_TAB = "target_tab"
        private const val TARGET_STOCK = "stock"
        private const val TARGET_REVIEW = "review"
        private const val TARGET_COMMUNITY = "community"
        private const val TARGET_QUANT = "quant"

        fun reviewIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_TARGET_TAB, TARGET_REVIEW)
        }
    }
}
