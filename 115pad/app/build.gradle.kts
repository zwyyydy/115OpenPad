plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    // baseline profile 的生成与打包（生成模块见 :baselineprofile）
    id("androidx.baselineprofile")
}

android {
    namespace = "com.open115.pad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.open115.pad"
        minSdk = 26
        targetSdk = 35
        // 这里的版本号 = 本次要发布的版本（发版提交在 release 线上做，见 tools/release_notes）
        versionCode = 9
        versionName = "0.8.0"
    }

    signingConfigs {
        // 个人分发：release 直接用默认 debug keystore 签名（免密钥管理；正式上架再换正式签名）
    }
    buildTypes {
        release {
            // 2026-10-25 暂关 R8：fullMode=false 之下仍出 VerifyError（wide register
            // 误编译，点播放必崩），先保证能用，回稳后再查 R8 具体哪步坏的字节码。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        // 从源码直接装的 debug 包在「关于」里带 -dev 后缀（0.8.0-dev）：master 的版本号只在
        // 发版时前进，不加后缀的话"开发版"和"已发布的同名版本"在界面上完全分不出来，
        // 装机后看着像"没更新"。发布用的包在 release 线上构建、不受这里影响。
        debug {
            versionNameSuffix = "-dev"
        }
    }
    // 按 ABI 拆包。QuickJS 带四套 native 库，release 里每套约 4MB；
    // universal 包保留一份（模拟器/老设备兜底），正式分发优先用 arm64 那个。
    // 注意：debug 也会拆，装机时用 app-universal-debug.apk（或对应 ABI 的那个）。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
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
        compose = true
        // 设置页的版本号读 BuildConfig.VERSION_NAME，免得每次发版都漏改一处硬编码
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        checkReleaseBuilds = false // 个人分发：跳过 fatal lint
    }
    testOptions {
        // 单测里 android.jar 是空壳：NfoParser 出错时会 android.util.Log.w，
        // 不开这个开关就是 "Method w in android.util.Log not mocked" 把用例打挂
        unitTests.isReturnDefaultValues = true
    }
}

baselineProfile {
    // 不在每次 assemble 时生成（要跑真机/模拟器上的 Macrobenchmark，很慢）；
    // 需要时显式执行 :app:generateReleaseBaselineProfile。
    //
    // ⚠️ 生成任务会重装/清理目标设备上的本 App：**登录态、媒体库、操作记录会全部丢**。
    // 只在专用模拟器上跑，别拿存着数据的平板/日常模拟器跑（2026-10-23 已踩过）。
    automaticGenerationDuringBuild = false
    // 把生成结果落到源码树（app/src/release/generated/baselineProfiles），随仓库一起走
    saveInSrc = true
    // startup profile：Android 12+ 的 DEX 布局优化
    dexLayoutOptimization = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // 纯逻辑单测（批量重命名的算名规则：中文数字、Excel 式字母、扩展名边界）
    testImplementation("junit:junit:4.13.2")
    // nfo 解析器单测：平台自带的 XmlPullParser 在 JVM 单测里是 android.jar 的空壳（调用即抛），
    // 用 kxml2 —— 同一个 org.xmlpull.v1 API 的纯 Java 实现（Android 上也带着它）
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    // SAF 目录树遍历（文件夹上传）：DocumentsContract 的轻量封装
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // baseline profile 的安装器：Android 12 以下靠它把 profile 交给 ART
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.zxing:core:3.5.3")

    // 插件化 JS 源：QuickJS（带多 ABI 的 native .so），用户在 App 内写/导入 .js 当元数据源
    implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")

    // 汉字 → 拼音（媒体库按拼音/首字母搜索）。只用于入库/回填时算一次，不参与查询。
    // pinyin4j 是纯 Java + classpath 资源，Maven Central 有，不需要额外仓库。
    implementation("com.belerweb:pinyin4j:2.5.1")

    // 媒体库（类 Yamby）：影视/季集/演员/标签都是关系型查询（标题搜索、评分排序、
    // 按演员/标签筛选），JSON 全量遍历撑不住 → Room + KSP
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 生成 baseline profile 的 Macrobenchmark 模块（只在生成时用到，不进 APK）
    baselineProfile(project(":baselineprofile"))
}
