plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// ルートプロジェクト側 (`UniversityAttendanceApp` -> `Tasks` -> `release`) からも実行できるようフォワードタスクを即時登録
tasks.create("generateReleaseKeystore") {
    group = "release"
    description = "[:app:generateReleaseKeystore] リリース署名キーストアを確認・自動生成します"
    dependsOn(":app:generateReleaseKeystore")
}

tasks.create("buildReleaseApk") {
    group = "release"
    description = "[:app:buildReleaseApk] 署名済みリリースAPKをビルドして release/ フォルダに出力します"
    dependsOn(":app:buildReleaseApk")
}

tasks.create("buildReleaseBundle") {
    group = "release"
    description = "[:app:buildReleaseBundle] 署名済みリリースAABをビルドして release/ フォルダに出力します"
    dependsOn(":app:buildReleaseBundle")
}

tasks.create("packageReleaseDistribution") {
    group = "release"
    description = "[:app:packageReleaseDistribution] 単体テスト実行＋署名済みリリースAPK＆AABを一括作成します"
    dependsOn(":app:packageReleaseDistribution")
}

tasks.create("installReleaseApk") {
    group = "release"
    description = "[:app:installReleaseApk] 署名済みリリースAPKをビルドして接続中タブレットへインストールします"
    dependsOn(":app:installReleaseApk")
}

tasks.create("cleanReleaseDistribution") {
    group = "release"
    description = "[:app:cleanReleaseDistribution] release/ 配布フォルダをクリーンアップします"
    dependsOn(":app:cleanReleaseDistribution")
}
