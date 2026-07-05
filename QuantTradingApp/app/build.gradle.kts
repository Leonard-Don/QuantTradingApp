plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("androidx.baselineprofile")
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
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.leonarddon.quanttrading"
        minSdk = 26
        targetSdk = 35
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kapt {
    arguments {
        arg("room.incremental", "true")
    }
}
dependencies {
    baselineProfile(project(":benchmarks"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")
