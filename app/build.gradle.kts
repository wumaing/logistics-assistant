plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.yymm.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yymm.monitor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Room 生成 schema 到该目录，供版本迁移使用
    kapt {
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // 按需求使用 kapt（非 ksp），豁免相关建议
        disable += "KaptUsageInsteadOfKsp"
        // 自适应图标必须放 mipmap-anydpi-v26（移走会 AAPT 资源找不到），此警告为误报
        disable += "ObsoleteSdkInt"
        // 电池白名单引导是本地个人工具的核心需求（不上架商店），PRD 明确要求
        disable += "BatteryLife"
    }

    testOptions {
        unitTests.all {
            // 项目路径含中文（d:\huihua\浏览助手）。
            // 该 JVM 参数对 Windows 上 JDK 18+ 无效（sun.jnu.encoding 原生只读），
            // 测试在中文路径下运行会 ClassNotFound（Gradle 已知限制）。
            // 已验证：将项目复制到 ASCII 路径（如 d:/huihua_ascii）后 testDebugUnitTest 全部通过。
            // 真正根治需：项目迁到无中文目录，或系统开启"Beta: 使用 Unicode UTF-8 提供全球语言支持"。
            it.jvmArgs("-Dsun.jnu.encoding=UTF-8")
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // Core / Lifecycle / Activity
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Material Components（提供 View 系统主题 Theme.Material3.*）
    implementation("com.google.android.material:material:1.11.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.0.0")
    // 显式升级 navigation-compose：hilt-navigation-compose 1.0.0 传递依赖 navigation 2.4.0，
    // 太老导致 hiltViewModel() 在真机报 SAVED_STATE_REGISTRY_OWNER_KEY 缺失崩溃（实机验证发现）
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room (kapt)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Coroutines + Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // 本地 OCR（tesseract）：读取司机端截屏订单文本，纯本地识别不上传
    implementation("com.rmtheis:tess-two:9.1.0")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
