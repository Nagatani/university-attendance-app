# 大学講義 出席管理システム (Androidタブレット向けアプリ)

MIFARE Classic 方式の学生証からセクター鍵認証を用いて学籍番号を読み取り、**「講義」「講義回」「学籍番号」「出席登録日時」**をデータベースに記録し、所定のフォルダや共有先へCSVエクスポートできるAndroidタブレット向けネイティブアプリ（Kotlin + Jetpack Compose + Room）です。

---

## 主な機能

1. **MIFARE Classic 学生証のセクター鍵認証・ブロック読み取り（外付けUSB NFCリーダー ＆ 本体内蔵NFC 両対応）**
   - **外付け USB NFCリーダー (PC/SC CCID規格) 対応**:
     - Android の `UsbManager` による USB CCID プロトコル (`PC_to_RDR_IccPowerOn`, `PC_to_RDR_XfrBlock` 等) と PC/SC Part 3 非接触ICコマンド (`FF CA` UID取得、`FF 82` 鍵ロード、`FF 86`/`FF 88` MIFAREセクター認証、`FF B0` 16バイトブロック読み取り) をネイティブ実装しています。
     - **ACS ACR122U / ACR1252U / ACR1281U**、**Sony RC-S300 (PaSoRi)**、**Identiv uTrust 3700F**、**HID OMNIKEY** 等の汎用 PC/SC (CCID) 非接触ICカードリーダーを USB-C / OTG で接続するだけで自動認識し、タブレット本体にNFCがない機種（または本体NFCがMIFARE Classic非対応の機種）でも確実にMIFARE Classic学生証を読み取れます。
   - **タブレット本体内蔵NFC 対応**:
     - `android.nfc.tech.MifareClassic` APIを使用し、指定したセクター番号に対して **Key A** または **Key B**（6バイト＝16進数12文字）で認証（`authenticateSectorWithKeyA` / `authenticateSectorWithKeyB`）を行い、16バイトデータブロックを読み取ります。
   - **アプリ内設定画面（MIFARE鍵設定タブ）** から以下を自在に変更可能です：
     - セクター番号（0〜39、1Kカードは0〜15）
     - セクター内ブロック番号（0〜2）
     - 認証鍵種別（Key A / Key B）と12桁HEXキー
     - 学籍番号の格納形式（`ASCII / UTF-8文字列`、`HEX 16進数文字列`、`BCD 二進化十進数`）
     - 16バイト内の抽出範囲（開始バイト位置 `Offset` 0〜15、読み取り長 `Length` 1〜16）
     - NULLパディング（`0x00`）や空白の自動除去、正規表現による抽出フィルタ
     - **USB NFCリーダー接続管理パネル**: 接続中のUSBリーダーの認識状態（VID/PID）確認や手動再検出・切り替えが可能
   - **リアルタイム・カード診断（16バイトブロック・インスペクタ）** 機能を搭載しており、外付けUSBリーダーまたは本体NFCに実際の学生証をタッチすると、16バイトのHEX/ASCII生データと抽出される学籍番号をその場で確認しながら設定を調整できます。

2. **講義・講義回の管理とタブレット向け「出席受付モード（全画面キオスク画面）」**
   - 講義（講義コード、講義名、担当教員、教室）と講義回（第1回〜第15回の一括作成または個別追加）を管理できます。
   - **「出席受付モードを開始（全画面）」** を押すと、教室入口のタブレット設置に最適な全画面タッチ待機モードに切り替わります：
     - 画面常時ON（スリープ抑止）＆ リアルタイムデジタル時計表示
     - 学生証タッチ時の大型視覚バナー表示（緑：出席完了、黄：重複タッチ通知、赤：認証・読取エラー）と効果音（ピピッ）
     - **同一講義回での重複出席登録の自動防止**（2回目以降のタッチでは初回登録時刻を表示）
     - 学生証忘れ等のための **手動入力補助**、および実機カードなしでも動作確認できる **テスト読取機能**
     - 学生による誤操作・勝手な画面終了を防ぐ **管理者PINロック機能**（任意設定）

3. **所定の場所へのCSVエクスポート・共有**
   - **所定の場所へワンタップCSV出力**: Androidの `OpenDocumentTree` (Storage Access Framework) により一度「所定の保存先フォルダ」（端末内フォルダやSDカード等）を指定しておけば、以後ボタン1つでそのフォルダへCSVファイルを直接出力します。
   - **保存先を指定して出力**: 毎回フォルダとファイル名を選択して保存することも可能です。
   - **他アプリへ共有**: Googleドライブ、メール、ファイルアプリ等へ直接CSVを送信できます。
   - **Excel対応（UTF-8 BOM付）**: 日本語の講義名などがWindows版/Mac版Microsoft Excelで文字化けしないよう、UTF-8 BOM（`\uFEFF`）付きで出力します。また登録方法列には `NFC(USB)` / `NFC(本体)` / `MANUAL` が記録されます。

---

## プロジェクト構成

```text
university-attendance-app/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/app/nagatani/dev/attendance/
│       │   │   ├── MainActivity.kt                        # NFC ReaderMode・USB接続監視・SAFランチャー・ナビゲーション
│       │   │   ├── data/
│       │   │   │   ├── local/
│       │   │   │   │   ├── Entities.kt                    # 講義・講義回・出席記録のRoomエンティティ
│       │   │   │   │   ├── AttendanceDao.kt               # DBクエリ・集計・CSV結合取得DAO
│       │   │   │   │   └── AppDatabase.kt                 # Roomデータベース定義＆初期サンプルデータ投入
│       │   │   │   └── preferences/
│       │   │   │       └── MifareSettingsRepository.kt    # MIFARE鍵（一括テキスト対応）・セクター設定＆保存先フォルダ設定の永続化
│       │   │   ├── nfc/
│       │   │   │   └── MifareClassicReader.kt             # 本体NFC用MIFARE Classic鍵認証・16バイト読取・学籍番号デコーダ
│       │   │   ├── usb/
│       │   │   │   └── UsbCcidMifareReader.kt             # 外付けUSB NFCリーダー (CCID / PC/SC APDU) ドライバ＆監視マネージャ
│       │   │   ├── export/
│       │   │   │   └── CsvExportManager.kt                # 所定フォルダ出力・SAF保存・共有シート連携
│       │   │   ├── util/
│       │   │   │   └── SoundFeedbackManager.kt            # タッチ時の効果音生成（成功/重複/エラー）
│       │   │   └── ui/
│       │   │       ├── AttendanceViewModel.kt             # 状態管理ViewModel
│       │   │       ├── theme/Theme.kt                     # Material3テーマ
│       │   │       └── screens/
│       │   │           ├── CourseManagementScreen.kt      # 講義・講義回管理（タブレット2ペイン画面）
│       │   │           ├── AttendanceKioskScreen.kt       # 出席受付モード（全画面キオスク画面）
│       │   │           ├── ExportAndHistoryScreen.kt      # 所定フォルダ設定・CSV出力・全履歴検索画面
│       │   │           └── MifareSettingsScreen.kt        # MIFARE鍵設定（一括テキスト指定）・USBリーダー管理＆16バイト診断画面
│       │   └── res/
│       │       └── xml/
│       │           ├── nfc_tech_filter.xml                # 本体NFC用フィルタ
│       │           ├── usb_device_filter.xml              # 外付けUSB CCID NFCリーダー自動認識フィルタ
│       │           └── file_paths.xml                     # CSV共有用FileProviderパス
│       └── test/
│           └── java/app/nagatani/dev/attendance/
│               └── MifareAndCsvTest.kt                    # MIFAREデコード・USB CCID APDU・CSV出力の単体テスト
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/libs.versions.toml
```

---

## ビルド・実行方法

1. **Android Studio**（Ladybug / Koala 以降推奨）を起動し、**Open** からこのプロジェクトフォルダ（`/Users/nagatani/.gemini/antigravity/scratch/university-attendance-app`）を開きます。
2. Gradle Sync が完了したら、Androidタブレット実機（またはエミュレータ）を接続して **Run (`app`)** を実行してください。

> **ハードウェアに関する補足（MIFARE Classic 対応について）**
> MIFARE Classic の独自の暗号化方式（Crypto-1）によるセクター認証（`authenticateSectorWithKeyA/B`）を行うには、Androidタブレット本体の内蔵NFCコントローラが **NXP製チップ等（MIFARE Classicサポート対象チップ）** である必要があります。なお、検証時にMIFARE Classic非対応カードでもテストしたい場合は、「MIFARE鍵設定」画面の「テスト用フォールバック（UID読み取り）」をONにするか、「テスト読取」ボタンをご利用いただけます。
