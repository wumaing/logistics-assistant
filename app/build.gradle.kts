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
    }

    testOptions {
        unitTests.all {
            // 项目路径含中文（d:\huihua\浏览助手），Gradle worker classpath argfile 以 UTF-8 写入，
            // JVM 默认 sun.jnu.encoding=GBK 解码导致中文路径乱码、测试类 ClassNotFound。
            // 强制 worker 以 UTF-8 解码文件名，修复中文路径下的单测执行。
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

    // Room (kapt)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Coroutines + Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
