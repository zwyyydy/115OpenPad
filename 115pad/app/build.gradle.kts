plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.open115.pad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.open115.pad"
        minSdk = 26
        targetSdk = 35
        // 这里的版本号 = **最近一次发布的版本**（发版提交在 release 线上做，见 tools/release_notes）
        versionCode = 7
        versionName = "0.7.0"
    }

    signingConfigs {
        // 个人分发：release 直接用默认 debug keystore 签名（免密钥管理；正式上架再换正式签名）
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        // 从源码直接装的 debug 包在「关于」里带 -dev 后缀（0.7.0-dev）：master 的版本号只在
        // 发版时前进，不加后缀的话"开发版"和"已发布的同名版本"在界面上完全分不出来，
        // 装机后看着像"没更新"。发布用的包在 release 线上构建、不受这里影响。
        debug {
            versionNameSuffix = "-dev"
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

    // 媒体库（类 Yamby）：影视/季集/演员/标签都是关系型查询（标题搜索、评分排序、
    // 按演员/标签筛选），JSON 全量遍历撑不住 → Room + KSP
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
