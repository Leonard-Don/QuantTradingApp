plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

val quanttradingPrivacyPolicyUrl = (project.findProperty("quanttradingPrivacyPolicyUrl") as? String).orEmpty()
val quanttradingTermsUrl = (project.findProperty("quanttradingTermsUrl") as? String).orEmpty()
val quanttradingDataDisclaimerUrl = (project.findProperty("quanttradingDataDisclaimerUrl") as? String).orEmpty()
val quanttradingSupportEmail = (project.findProperty("quanttradingSupportEmail") as? String).orEmpty()
val quanttradingReleaseKeystore = (project.findProperty("quanttradingReleaseKeystore") as? String).orEmpty()
val quanttradingReleaseStorePassword = (project.findProperty("quanttradingReleaseStorePassword") as? String).orEmpty()
val quanttradingReleaseKeyAlias = (project.findProperty("quanttradingReleaseKeyAlias") as? String).orEmpty()
val quanttradingReleaseKeyPassword = (project.findProperty("quanttradingReleaseKeyPassword") as? String).orEmpty()
val hasQuantTradingReleaseSigning = listOf(
    quanttradingReleaseKeystore,
    quanttradingReleaseStorePassword,
    quanttradingReleaseKeyAlias,
    quanttradingReleaseKeyPassword,
).all { it.isNotBlank() }

android {
    namespace = "io.github.leonarddon.quanttrading"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.leonarddon.quanttrading"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${quanttradingPrivacyPolicyUrl.escapeForBuildConfig()}\"")
        buildConfigField("String", "TERMS_OF_SERVICE_URL", "\"${quanttradingTermsUrl.escapeForBuildConfig()}\"")
        buildConfigField("String", "DATA_DISCLAIMER_URL", "\"${quanttradingDataDisclaimerUrl.escapeForBuildConfig()}\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"${quanttradingSupportEmail.escapeForBuildConfig()}\"")
        buildConfigField("String", "TENCENT_QUOTE_BASE_URL", "\"https://qt.gtimg.cn/q=\"")
        buildConfigField("String", "TENCENT_KLINE_URL", "\"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=\"")
        buildConfigField("String", "SINA_QUOTE_BASE_URL", "\"https://hq.sinajs.cn/list=\"")
        buildConfigField("String", "EASTMONEY_KLINE_URL", "\"https://push2his.eastmoney.com/api/qt/stock/kline/get\"")
    }
    signingConfigs {
        if (hasQuantTradingReleaseSigning) {
            create("releaseUpload") {
                storeFile = file(quanttradingReleaseKeystore)
                storePassword = quanttradingReleaseStorePassword
                keyAlias = quanttradingReleaseKeyAlias
                keyPassword = quanttradingReleaseKeyPassword
            }
        }
    }
    buildTypes {
        debug {
        }
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            matchingFallbacks += listOf("debug")
        }
        release {
            if (hasQuantTradingReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}
dependencies {
    baselineProfile(project(":benchmarks"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")
