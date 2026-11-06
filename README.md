# 大学講義 出席管理システム

学生証（MIFARE Classic）をタッチするだけで講義の出席を記録できる、Android タブレット向けアプリです。
記録した出席データは CSV で出力でき、Excel でそのまま開けます。

- 本体 NFC と外付け USB NFC リーダー（Sony RC-S300、ACS ACR122U など）の両方に対応
- 教室の入口に置けるよう、全画面の「出席受付モード」を用意（管理者 PIN でロック可能）
- 同じ講義回での二重登録を自動で防止
- 学生証を忘れた学生向けに、手動での出席登録が可能
- データは端末内にのみ保存され、クラウドバックアップの対象になりません

## 動作環境

- Android 8.0（API 26）以降のタブレット（スマートフォンでも利用可能）
- MIFARE Classic を読める NFC 環境
  - 本体 NFC の場合、NXP 製など MIFARE Classic 対応チップを搭載した機種が必要です
  - 対応していない機種では、外付けの USB NFC リーダー（PC/SC CCID 対応）を接続してください

## インストール

1. [Releases](https://github.com/Nagatani/university-attendance-app/releases) から最新の `university-attendance-v*-release.apk` をダウンロードします
2. タブレットで APK を開き、「提供元不明のアプリ」のインストールを許可してインストールします

初期設定と日々の使い方は [利用ガイド](docs/user-guide.md) を参照してください。

## ドキュメント

| ドキュメント | 内容 |
| :--- | :--- |
| [利用ガイド](docs/user-guide.md) | 初期設定、講義の登録、出席受付、CSV 出力など日々の運用手順 |
| [カードリーダーと学生証の設定](docs/card-reader-setup.md) | MIFARE 鍵の書き方、学籍番号の抽出設定、USB リーダーの接続 |
| [CSV ファイルの仕様](docs/csv-format.md) | 出力される列、文字コード、ファイル名、保存先 |
| [トラブルシューティング](docs/troubleshooting.md) | カードが読めない、CSV が見つからないなどの対処法 |
| [データの扱いとセキュリティ](docs/security-and-privacy.md) | 保存されるデータ、バックアップ、PIN の仕様と制限 |
| [開発ガイド](docs/development.md) | 開発環境、ビルド、テスト、コード構成、DB マイグレーション |
| [リリース手順](docs/release.md) | バージョンの上げ方、署名鍵の管理、GitHub Releases への公開 |
| [改善 TODO](docs/TODO.md) | 既知の課題と改善案の一覧 |

## 開発者向けクイックスタート

```bash
./gradlew assembleDebug        # デバッグ APK をビルド
./gradlew installDebug         # 接続中の端末へインストール
./gradlew testDebugUnitTest    # 単体テストを実行
```

ビルドには JDK 17〜21 が必要です（Android Studio 同梱の JDK を推奨）。詳細は [開発ガイド](docs/development.md) を参照してください。
