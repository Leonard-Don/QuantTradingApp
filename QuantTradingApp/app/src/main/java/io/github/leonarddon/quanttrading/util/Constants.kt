package io.github.leonarddon.quanttrading.util

import io.github.leonarddon.quanttrading.BuildConfig

object Constants {
    const val PREFS_NAME = "GuTongWealth"
    const val KEY_IS_VIP = "is_vip"
    const val KEY_VIP_EXPIRE = "vip_expire_time"

    val API_BASE_URL: String = BuildConfig.APP_API_BASE_URL
    const val CONNECT_TIMEOUT = 30L
    const val READ_TIMEOUT = 30L
}
