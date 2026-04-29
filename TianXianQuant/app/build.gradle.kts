plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val tianxianApiBaseUrl = (project.findProperty("tianxianApiBaseUrl") as? String)
    ?: "https://api.gutongwealth.com/"
val tianxianBackendSyncEnabled = (project.findProperty("tianxianBackendSyncEnabled") as? String)
    ?.toBooleanStrictOrNull()
    ?: false

android {
    namespace = "com.tianxian.quant"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tianxian.quant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "APP_API_BASE_URL", "\"$tianxianApiBaseUrl\"")
        buildConfigField("boolean", "ENABLE_BACKEND_ACCOUNT_SYNC", tianxianBackendSyncEnabled.toString())
        buildConfigField("String", "TENCENT_QUOTE_BASE_URL", "\"https://qt.gtimg.cn/q=\"")
        buildConfigField("String", "TENCENT_KLINE_URL", "\"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=\"")
        buildConfigField("String", "SINA_QUOTE_BASE_URL", "\"https://hq.sinajs.cn/list=\"")
        buildConfigField("String", "EASTMONEY_KLINE_URL", "\"https://push2his.eastmoney.com/api/qt/stock/kline/get\"")
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_LOCAL_PAYMENT_SIMULATION", "true")
        }
        release {
            buildConfigField("boolean", "ALLOW_LOCAL_PAYMENT_SIMULATION", "false")
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
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
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
}
