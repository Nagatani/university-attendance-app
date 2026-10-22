import java.io.FileInputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ============================================================================
// リリース署名キーストア設定の読み込み・自動準備ヘルパー
// ============================================================================
val keystorePropsFile = rootProject.file("keystore.properties")
val defaultKeystoreDir = rootProject.file("keystore")
val defaultKeystoreFile = File(defaultKeystoreDir, "release-keystore.jks")

/**
 * keystore.properties および keystore/release-keystore.jks が未作成の場合、
 * 実行中JDKの keytool を使って自己署名リリースキーストアを自動生成する。
 */
fun ensureReleaseKeystoreIfNeeded(): Properties {
    val props = Properties()
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { props.load(it) }
    }

    val envStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull
        ?: System.getenv("RELEASE_STORE_FILE")
        ?: props.getProperty("storeFile")

    if (envStoreFile == null && !defaultKeystoreFile.exists()) {
        runCatching {
            defaultKeystoreDir.mkdirs()
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val keytoolBin = if (isWindows) "keytool.exe" else "keytool"
            val keytoolFile = File(System.getProperty("java.home"), "bin/$keytoolBin")
            val keytoolCmd = if (keytoolFile.exists()) keytoolFile.absolutePath else keytoolBin

            val defaultPass = "AttendanceRelease2026!"
            val defaultAlias = "attendance_release"
            val proc = ProcessBuilder(
                keytoolCmd,
                "-genkeypair",
                "-v",
                "-keystore", defaultKeystoreFile.absolutePath,
                "-storetype", "PKCS12",
                "-alias", defaultAlias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-storepass", defaultPass,
                "-keypass", defaultPass,
                "-dname", "CN=University Attendance App, OU=Development, O=Nagatani, L=Tokyo, ST=Tokyo, C=JP"
            ).redirectErrorStream(true).start()
            proc.waitFor()

            if (defaultKeystoreFile.exists() && !keystorePropsFile.exists()) {
                keystorePropsFile.writeText(
                    """
                    # Release signing configuration for University Attendance App
                    # ご自身の署名キーストアを使用する場合は以下のパス・パスワード・エイリアスを変更してください
                    storeFile=keystore/release-keystore.jks
                    storePassword=$defaultPass
                    keyAlias=$defaultAlias
                    keyPassword=$defaultPass
                    """.trimIndent() + "\n"
                )
                FileInputStream(keystorePropsFile).use { props.load(it) }
            }
        }
    }
    return props
}

val keystoreProps = ensureReleaseKeystoreIfNeeded()

fun resolveSigningParam(propKey: String, gradleAndEnvKey: String, defaultValue: String): String {
    return providers.gradleProperty(gradleAndEnvKey).orNull
        ?: System.getenv(gradleAndEnvKey)
        ?: keystoreProps.getProperty(propKey)
        ?: defaultValue
}

val resolvedStoreFilePath = resolveSigningParam(
    propKey = "storeFile",
    gradleAndEnvKey = "RELEASE_STORE_FILE",
    defaultValue = "keystore/release-keystore.jks"
)
val resolvedStoreFile = rootProject.file(resolvedStoreFilePath)
val resolvedStorePassword = resolveSigningParam(
    propKey = "storePassword",
    gradleAndEnvKey = "RELEASE_STORE_PASSWORD",
    defaultValue = "AttendanceRelease2026!"
)
val resolvedKeyAlias = resolveSigningParam(
    propKey = "keyAlias",
    gradleAndEnvKey = "RELEASE_KEY_ALIAS",
    defaultValue = "attendance_release"
)
val resolvedKeyPassword = resolveSigningParam(
    propKey = "keyPassword",
    gradleAndEnvKey = "RELEASE_KEY_PASSWORD",
    defaultValue = resolvedStorePassword
)
val enableReleaseMinify = (providers.gradleProperty("releaseMinify").orNull ?: "false").toBoolean()

android {
    namespace = "app.nagatani.dev.attendance"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.nagatani.dev.attendance"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (resolvedStoreFile.exists()) {
                storeFile = resolvedStoreFile
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = enableReleaseMinify
            isShrinkResources = enableReleaseMinify
            signingConfig = if (resolvedStoreFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation("junit:junit:4.13.2")
    debugImplementation(libs.androidx.ui.tooling)
}

// ============================================================================
// リリースビルド・配布パッケージ作成用 Gradle カスタムタスク (group = "release")
// ============================================================================

val releaseDistDir = rootProject.file("release")
val appVersionName = android.defaultConfig.versionName ?: "1.0.0"
val appVersionCode = android.defaultConfig.versionCode ?: 1

fun computeSha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 1. リリース署名キーストアの確認・生成タスク
 */
val generateReleaseKeystore by tasks.registering {
    group = "release"
    description = "リリース署名用のキーストア (keystore/release-keystore.jks) と keystore.properties を確認・自動生成します"

    doLast {
        ensureReleaseKeystoreIfNeeded()
        if (resolvedStoreFile.exists()) {
            println("========================================")
            println("[Release Keystore Ready]")
            println("Keystore File : ${resolvedStoreFile.absolutePath}")
            println("Key Alias     : $resolvedKeyAlias")
            println("Config File   : ${keystorePropsFile.absolutePath}")
            println("========================================")
        } else {
            println("[Warning] リリースキーストアが見つからないため debug キーでフォールバック署名されます。")
        }
    }
}

/**
 * 2. 署名済みリリースAPKをビルドし、プロジェクト直下の release/ フォルダにコピー＆SHA-256出力するタスク
 */
val buildReleaseApk by tasks.registering {
    group = "release"
    description = "署名済みリリースAPKをビルドし、release/ フォルダへバージョン名付きAPKとSHA-256チェックサムを出力します"
    dependsOn(generateReleaseKeystore, "assembleRelease")

    doLast {
        val apkSourceDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val builtApk = apkSourceDir.walkTopDown()
            .firstOrNull { it.isFile && it.extension == "apk" }
            ?: throw GradleException("リリースAPKが生成されませんでした: ${apkSourceDir.absolutePath}")

        releaseDistDir.mkdirs()

        val versionedApkName = "university-attendance-v${appVersionName}-b${appVersionCode}-release.apk"
        val latestApkName = "university-attendance-latest-release.apk"

        val versionedApkFile = File(releaseDistDir, versionedApkName)
        val latestApkFile = File(releaseDistDir, latestApkName)

        builtApk.copyTo(versionedApkFile, overwrite = true)
        builtApk.copyTo(latestApkFile, overwrite = true)

        val sha256 = computeSha256Hex(versionedApkFile)
        val sha256File = File(releaseDistDir, "$versionedApkName.sha256")
        sha256File.writeText("$sha256  $versionedApkName\n")

        val sizeMb = String.format("%.2f MB", versionedApkFile.length().toDouble() / (1024.0 * 1024.0))
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val infoFile = File(releaseDistDir, "release-info.txt")
        infoFile.writeText(
            """
            ============================================================
            大学講義 出席管理システム - リリースビルド情報
            ============================================================
            Application ID : ${android.defaultConfig.applicationId}
            Version Name   : $appVersionName
            Version Code   : $appVersionCode
            Build Time     : $timestamp
            Signed By      : ${if (resolvedStoreFile.exists()) resolvedStoreFile.name else "debug.keystore (fallback)"}
            R8 Minify      : $enableReleaseMinify
            ------------------------------------------------------------
            [APK Artifact]
            File Name      : $versionedApkName
            Latest Alias   : $latestApkName
            File Size      : $sizeMb (${versionedApkFile.length()} bytes)
            SHA-256        : $sha256
            Full Path      : ${versionedApkFile.absolutePath}
            ------------------------------------------------------------
            [ADB Install Command]
            adb install -r "${versionedApkFile.absolutePath}"
            ============================================================
            """.trimIndent() + "\n"
        )

        println("============================================================")
        println("[Release APK Build Succeeded]")
        println("Output APK  : ${versionedApkFile.absolutePath}")
        println("Latest Copy : ${latestApkFile.absolutePath}")
        println("File Size   : $sizeMb")
        println("SHA-256     : $sha256")
        println("Release Info: ${infoFile.absolutePath}")
        println("============================================================")
    }
}

/**
 * 3. リリース用 Android App Bundle (.aab) をビルドして release/ フォルダへ出力するタスク
 */
val buildReleaseBundle by tasks.registering {
    group = "release"
    description = "リリース用 Android App Bundle (.aab) をビルドし、release/ フォルダへ出力します"
    dependsOn(generateReleaseKeystore, "bundleRelease")

    doLast {
        val bundleSourceDir = layout.buildDirectory.dir("outputs/bundle/release").get().asFile
        val builtAab = bundleSourceDir.walkTopDown()
            .firstOrNull { it.isFile && it.extension == "aab" }
            ?: throw GradleException("リリースAABが生成されませんでした: ${bundleSourceDir.absolutePath}")

        releaseDistDir.mkdirs()

        val versionedAabName = "university-attendance-v${appVersionName}-b${appVersionCode}-release.aab"
        val versionedAabFile = File(releaseDistDir, versionedAabName)
        builtAab.copyTo(versionedAabFile, overwrite = true)

        val sha256 = computeSha256Hex(versionedAabFile)
        File(releaseDistDir, "$versionedAabName.sha256").writeText("$sha256  $versionedAabName\n")

        val sizeMb = String.format("%.2f MB", versionedAabFile.length().toDouble() / (1024.0 * 1024.0))
        println("============================================================")
        println("[Release AAB Bundle Build Succeeded]")
        println("Output AAB  : ${versionedAabFile.absolutePath}")
        println("File Size   : $sizeMb")
        println("SHA-256     : $sha256")
        println("============================================================")
    }
}

/**
 * 4. ユニットテスト実行 ＋ リリースAPK ＆ リリースAAB を一括生成する総合配布タスク
 */
val packageReleaseDistribution by tasks.registering {
    group = "release"
    description = "Releaseユニットテストを実行した上で、署名済みリリースAPKとAABの両方を release/ フォルダへ一括生成します"
    dependsOn("testReleaseUnitTest", buildReleaseApk, buildReleaseBundle)

    doLast {
        println("============================================================")
        println("[Release Distribution Package Complete]")
        println("配布用ディレクトリ: ${releaseDistDir.absolutePath}")
        releaseDistDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            println(" - ${f.name} (${f.length()} bytes)")
        }
        println("============================================================")
    }
}

tasks.named("buildReleaseApk") {
    mustRunAfter("testReleaseUnitTest")
}
tasks.named("buildReleaseBundle") {
    mustRunAfter("testReleaseUnitTest")
}

/**
 * 5. 署名済みリリースAPKをビルドして、USB/Wi-Fi接続中のAndroidタブレットへ直接インストールするタスク
 */
val installReleaseApk by tasks.registering {
    group = "release"
    description = "署名済みリリースAPKをビルドし、接続中のAndroidタブレット実機へ adb install -r でインストールします"
    dependsOn(buildReleaseApk)

    doLast {
        val latestApkFile = File(releaseDistDir, "university-attendance-latest-release.apk")
        if (!latestApkFile.exists()) {
            throw GradleException("インストール対象のリリースAPKが見つかりません: ${latestApkFile.absolutePath}")
        }

        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            FileInputStream(localPropsFile).use { localProps.load(it) }
        }
        val sdkDir = localProps.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val adbBinName = if (isWindows) "adb.exe" else "adb"
        val adbExecutable = if (!sdkDir.isNullOrBlank()) {
            val candidate = File(sdkDir, "platform-tools/$adbBinName")
            if (candidate.exists()) candidate.absolutePath else adbBinName
        } else {
            adbBinName
        }

        println("Installing ${latestApkFile.name} via $adbExecutable ...")
        val proc = ProcessBuilder(adbExecutable, "install", "-r", latestApkFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        println(output)
        if (exitCode != 0) {
            throw GradleException("adb install に失敗しました (exitCode=$exitCode)。タブレットがUSBデバッグ接続されているか確認してください。")
        }
        println("[Success] リリースAPKをタブレットへインストールしました。")
    }
}

/**
 * 6. release/ 配布フォルダのクリーンアップタスク
 */
val cleanReleaseDistribution by tasks.registering(Delete::class) {
    group = "release"
    description = "プロジェクト直下の release/ 配布出力フォルダをクリーンアップします"
    delete(releaseDistDir)
}
