# 開発ガイド

- [開発環境](#開発環境)
- [ビルドと実行](#ビルドと実行)
- [テスト](#テスト)
- [技術スタック](#技術スタック)
- [コード構成](#コード構成)
- [処理の流れ](#処理の流れ)
- [データベース](#データベース)
- [実装上の注意](#実装上の注意)

## 開発環境

| ツール | バージョン |
| :--- | :--- |
| JDK | 25（LTS）を推奨。17 以上で動作 |
| Android Studio | AGP 9.5 に対応したバージョン（最新の安定版を推奨） |
| Gradle | 9.8.2（Gradle Wrapper 同梱） |
| Android Gradle Plugin | 9.5.1 |
| Kotlin | 2.4.21（AGP の組み込み Kotlin サポートを使用） |
| compileSdk / targetSdk | 34 |
| minSdk | 26（Android 8.0） |

> ターミナルからビルドする場合は、`JAVA_HOME` の JDK が使われます。Android Studio では **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** で選んだ JDK が使われます。GitHub Actions のリリースビルドは JDK 25 で行います。
> JDK 25 でビルドすると `WARNING: A restricted method in java.lang.System has been called` という警告が出ますが、Gradle 自体の警告で、ビルドへの影響はありません。

## ビルドと実行

### Android Studio

1. このリポジトリのフォルダを **Open** で開きます
2. Gradle Sync の完了後、実機を接続して **Run 'app'** を実行します

NFC と USB リーダーの動作確認には実機が必要です。エミュレータでも画面操作や「テスト読取」「サンプルデータで検証」は試せます。

### ターミナル

```bash
./gradlew assembleDebug       # デバッグ APK（app/build/outputs/apk/debug/）
./gradlew installDebug        # 接続中の端末へインストール
./gradlew testDebugUnitTest   # 単体テスト
```

リリースビルドについては [リリース手順](release.md) を参照してください。

## テスト

単体テストは `app/src/test/java/app/nagatani/dev/attendance/MifareAndCsvTest.kt` にあります。Android 実機なしで JVM 上で実行できます。

```bash
./gradlew testDebugUnitTest
```

テストしている内容:

- MIFARE 鍵テキストのパース（複数行、セクター指定、鍵種別指定）
- 16 バイトブロックからの学籍番号の抽出（ASCII / BCD / HEX）
- USB CCID / PC/SC の APDU とフレームの組み立て
- CSV の生成（列、エスケープ、数式の無効化）

ViewModel・DAO・DB マイグレーション・USB ポーリングのテストはまだありません。

## 技術スタック

- Kotlin 2.4 / Jetpack Compose（Material 3）
- Room（SQLite）、KSP2
- Kotlin Coroutines / Flow
- Android NFC API（`NfcAdapter` ReaderMode、`MifareClassic`）
- Android USB Host API（`UsbManager`）による USB CCID 通信の独自実装
- Storage Access Framework / MediaStore / FileProvider（CSV 出力）

依存ライブラリのバージョンは `gradle/libs.versions.toml` で管理しています。

## コード構成

パッケージ: `app.nagatani.dev.attendance`

```text
app/src/main/java/app/nagatani/dev/attendance/
├── MainActivity.kt                 # NFC ReaderMode、USB 接続イベント、SAF ランチャー、画面切り替え
├── data/
│   ├── local/
│   │   ├── Entities.kt             # Room エンティティ（講義・講義回・出席記録）と結合モデル
│   │   ├── AttendanceDao.kt        # クエリ（一覧、出席者数集計、CSV 用の結合取得）
│   │   └── AppDatabase.kt          # DB 定義、初回起動時のサンプルデータ作成
│   └── preferences/
│       └── MifareSettingsRepository.kt  # 読み取り設定・出力設定の保存、鍵テキストのパーサ
├── nfc/
│   └── MifareClassicReader.kt      # 本体 NFC での認証・読み取り、学籍番号の抽出
├── usb/
│   └── UsbCcidMifareReader.kt      # USB CCID / PC/SC プロトコル実装、リーダーの検出・ポーリング
├── export/
│   └── CsvExportManager.kt         # CSV 生成、保存（所定フォルダ / SAF / 共有）
├── util/
│   └── SoundFeedbackManager.kt     # 効果音（成功 / 重複 / エラー）
└── ui/
    ├── AttendanceViewModel.kt      # 画面状態、出席登録、CSV 出力の制御
    ├── theme/Theme.kt
    └── screens/
        ├── CourseManagementScreen.kt    # 講義・講義回管理
        ├── AttendanceKioskScreen.kt     # 出席受付モード
        ├── ExportAndHistoryScreen.kt    # CSV 出力設定・全履歴
        └── MifareSettingsScreen.kt      # 読み取り設定・USB リーダー管理・カード診断
```

その他の主なファイル:

| パス | 内容 |
| :--- | :--- |
| `app/src/main/res/xml/nfc_tech_filter.xml` | アプリを起動する NFC タグの種類 |
| `app/src/main/res/xml/usb_device_filter.xml` | アプリを起動する USB 機器（CCID クラスと主要メーカーの VID） |
| `app/src/main/res/xml/data_extraction_rules.xml` | バックアップ・端末移行からの除外設定 |
| `app/src/main/res/xml/file_paths.xml` | 共有用 CSV の FileProvider パス |
| `app/schemas/` | Room が出力する DB スキーマの履歴 |
| `app/build.gradle.kts` | ビルド設定、署名設定、リリース用 Gradle タスク |
| `.github/workflows/release.yml` | タグ push で APK を GitHub Releases に公開するワークフロー |

画面は、画面幅 720dp 以上のタブレット用レイアウトと、それ未満のスマートフォン用レイアウトを同じファイル内で出し分けています（`isCompact`）。

## 処理の流れ

### カード読み取りから出席登録まで

```text
本体 NFC:  NfcAdapter ReaderMode → MainActivity.onTagDiscovered
           → AttendanceViewModel.onNfcTagDiscovered → MifareClassicReader.readStudentCard
USB:       UsbNfcReaderManager のポーリングループ（IO スレッド）→ onCardReadResult
                                   ↓
                AttendanceViewModel.handleMifareReadResult
                  ├ 同じ UID の 1.2 秒以内の再検知は無視
                  ├ 出席受付モード以外 → 結果を通知するだけ（設定画面ではカード診断に表示）
                  └ 出席受付モード → registerAttendanceInternal
                        ├ メモリ上のキャッシュで重複判定 → 重複なら黄バナー
                        ├ 先に緑バナーと効果音を出す
                        └ DB に保存（失敗したらバナーを取り消してエラー表示）
```

- 読み取りに使う設定は、設定画面を開いている間は **保存前の編集内容**、それ以外では保存済みの設定です（`getEffectiveMifareConfig`）
- 低速な端末でも待たせないよう、DB への書き込み完了を待たずに成功を表示します。DB には `(sessionId, studentId)` のユニーク制約があり、重複は DB 側でも防いでいます
- 本体 NFC は `FLAG_READER_SKIP_NDEF_CHECK` で NDEF の探索を省略し、読み取りを速くしています

### USB リーダー

`UsbCcidMifareReader.kt` には次の 2 つがあります。

- `UsbCcidProtocol`: CCID のフレーム（`PC_to_RDR_IccPowerOn`、`PC_to_RDR_XfrBlock`、`PC_to_RDR_Escape` など）と PC/SC の APDU（`FF CA` UID 取得、`FF 82` 鍵ロード、`FF 86` / `FF 88` 認証、`FF B0` 読み取り）を組み立てる
- `UsbNfcReaderManager`: USB 機器の検出、接続許可の要求、ポーリングループ、カードの読み取り

Sony RC-S300 はカードを離したときにリーダーが固まりやすいため、Escape コマンドの送信を最初の 1 回に限るなどの専用処理があります。この部分を変更する場合は、必ず実機で連続読み取りを確認してください。

## データベース

DB 名は `university_attendance.db`、現在のスキーマバージョンは 1 です。

| テーブル | 主な列 | 制約 |
| :--- | :--- | :--- |
| `courses` | `id`、`courseCode`、`courseName`、`instructorName`、`scheduleAndRoom`、`createdAt` | |
| `lecture_sessions` | `id`、`courseId`、`sessionNumber`、`sessionTitle`、`sessionDate`、`createdAt` | `(courseId, sessionNumber)` がユニーク。講義の削除で連鎖削除 |
| `attendance_records` | `id`、`courseId`、`sessionId`、`studentId`、`cardUid`、`registeredAt`、`registrationMethod`、`note` | `(sessionId, studentId)` がユニーク。講義・講義回の削除で連鎖削除 |

### スキーマを変更するとき

出席記録を失わないよう、破壊的マイグレーション（`fallbackToDestructiveMigration`）は使っていません。スキーマを変える場合は次の手順で行ってください。
マイグレーションを用意せずにバージョンだけ上げると、更新後のアプリが起動時にクラッシュします。

1. エンティティを変更し、`AppDatabase` の `version` を 1 つ上げる
2. ビルドすると、`app/schemas/app.nagatani.dev.attendance.data.local.AppDatabase/<新バージョン>.json` が出力される
3. 旧バージョンの JSON との差分をもとに `Migration` を書き、`Room.databaseBuilder(...).addMigrations(...)` に追加する
4. 生成されたスキーマ JSON もコミットする
5. 旧バージョンのアプリでデータを作った端末に新しい APK を上書きインストールし、データが残ることを確認する

## 実装上の注意

- `CourseEntity` を `insertCourse`（`REPLACE`）で更新すると、連鎖削除で講義回と出席記録が消えます。講義を編集する機能を作るときは、既存の `updateCourse`（`@Update`）を使ってください
- `MifareConfig` は鍵と PIN を含むため、`toString()` の結果をログに出さないでください
- 画面の文言と色はコード内に直接書かれています（`strings.xml` には未移行）
- 既知の課題と改善案は [改善 TODO](TODO.md) にまとめています。対応したら TODO も更新してください
