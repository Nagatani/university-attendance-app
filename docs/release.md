# リリース手順

署名済み APK をビルドし、GitHub Releases で配布する手順です。

- [概要](#概要)
- [リリースの手順](#リリースの手順)
- [署名鍵（キーストア）の管理](#署名鍵キーストアの管理)
- [GitHub Actions の初回設定](#github-actions-の初回設定)
- [ワークフローの動作](#ワークフローの動作)
- [ローカルでのリリースビルド](#ローカルでのリリースビルド)
- [手動で GitHub Releases に公開する](#手動で-github-releases-に公開する)
- [うまくいかないとき](#うまくいかないとき)

## 概要

```text
versionCode / versionName を上げてコミット
        ↓
git tag v1.2.0 && git push origin main v1.2.0
        ↓
GitHub Actions（.github/workflows/release.yml）
  タグとバージョンの一致を確認 → 署名鍵を復元 → テスト → 署名済み APK をビルド
        ↓
GitHub Releases に APK と SHA-256 が公開される
```

配布するのは APK だけです。AAB（Android App Bundle）は Google Play 向けの形式で、端末に直接インストールできないため公開しません。

## リリースの手順

### 1. バージョンを上げる

`app/build.gradle.kts` の `defaultConfig` を編集します。

```kotlin
versionCode = 2          // リリースのたびに必ず 1 以上増やす
versionName = "1.1.0"    // 利用者に見せるバージョン。タグ名と一致させる
```

- `versionCode` を増やさないと、Android は新しい APK をアップデートとして受け付けません
- `versionName` はタグ名から先頭の `v` を除いたものと一致させます（タグ `v1.1.0` ↔ `versionName = "1.1.0"`）。一致しないとワークフローが失敗します

バージョン番号の付け方の目安:

| 変更内容 | 例 |
| :--- | :--- |
| 不具合の修正だけ | `1.0.0` → `1.0.1` |
| 機能の追加 | `1.0.1` → `1.1.0` |
| DB や設定に互換性のない変更 | `1.1.0` → `2.0.0` |

### 2. コミットしてタグを push する

```bash
git add app/build.gradle.kts
git commit -m "Release v1.1.0"
git tag v1.1.0
git push origin main v1.1.0
```

### 3. 公開を確認する

```bash
gh run watch                                   # ワークフローの進み具合を見る
gh release view v1.1.0 -R Nagatani/university-attendance-app
```

リリースノートは、前回のタグ以降のコミットとプルリクエストから自動で作られます。必要に応じて GitHub の画面で編集してください。

### プレリリース

`v1.2.0-beta1` のようにハイフンを含むタグは、プレリリースとして公開されます。この場合も `versionName` を `"1.2.0-beta1"` にしてください。

## 署名鍵（キーストア）の管理

Android は、インストール済みのアプリと **同じ鍵で署名された APK** だけを上書き更新として受け付けます。
署名鍵を失うと、配布済みの端末ではアンインストール（＝全データ消去）しない限り更新できなくなります。

### 鍵の場所

| ファイル | 内容 |
| :--- | :--- |
| `keystore/release-keystore.jks` | 署名鍵本体 |
| `keystore.properties` | 鍵のパス・パスワード・エイリアス |

どちらも `.gitignore` に含まれており、リポジトリにはコミットされません。

> **必ずバックアップしてください。** この 2 ファイルを、リポジトリ以外の安全な場所（パスワードマネージャーや暗号化したストレージなど）に保管してください。GitHub Secrets に登録した値は、後から読み出せません。

### 鍵の自動生成

`keystore.properties` と `keystore/release-keystore.jks` がない状態でリリースビルドすると、JDK の `keytool` でランダムなパスワードの鍵が自動で作られます。

- 最初の 1 回だけ、開発者のマシンで作る想定です
- **すでに配布済みの場合は、新しい鍵を作ってはいけません。** バックアップから 2 ファイルを戻してください

### 鍵の指定方法

署名に使う鍵は、次の優先順位で決まります。

1. Gradle プロパティ（`-PRELEASE_STORE_FILE=...` など）
2. 環境変数
3. `keystore.properties`

| Gradle プロパティ / 環境変数 | `keystore.properties` のキー |
| :--- | :--- |
| `RELEASE_STORE_FILE` | `storeFile` |
| `RELEASE_STORE_PASSWORD` | `storePassword` |
| `RELEASE_KEY_ALIAS` | `keyAlias` |
| `RELEASE_KEY_PASSWORD` | `keyPassword` |

鍵ファイルが見つからない場合は、debug 鍵で署名されます（ビルド出力の `release-info.txt` の `Signed By` で確認できます）。debug 鍵で署名した APK は配布しないでください。

### 鍵の指紋を確かめる

配布中の APK と同じ鍵か確かめるには、証明書の SHA-256 指紋を比べます。

```bash
# 鍵ストアの指紋
keytool -list -v -keystore keystore/release-keystore.jks | grep SHA256

# APK の指紋（Android SDK の build-tools に含まれる apksigner）
apksigner verify --print-certs university-attendance-v1.1.0-b2-release.apk | grep SHA-256
```

## GitHub Actions の初回設定

### 1. Secrets を登録する

ワークフローは、リポジトリの Secrets から署名鍵を受け取ります。プロジェクトのルートで次を実行すると、ローカルの鍵がそのまま登録されます（リポジトリの管理権限が必要です）。

```bash
REPO=Nagatani/university-attendance-app
base64 -i keystore/release-keystore.jks | gh secret set RELEASE_KEYSTORE_BASE64 -R $REPO
gh secret set RELEASE_STORE_PASSWORD -R $REPO --body "$(sed -n 's/^storePassword=//p' keystore.properties)"
gh secret set RELEASE_KEY_ALIAS      -R $REPO --body "$(sed -n 's/^keyAlias=//p' keystore.properties)"
gh secret set RELEASE_KEY_PASSWORD   -R $REPO --body "$(sed -n 's/^keyPassword=//p' keystore.properties)"
gh secret list -R $REPO   # 4 件が登録されたことを確認
```

| Secret | 内容 |
| :--- | :--- |
| `RELEASE_KEYSTORE_BASE64` | `keystore/release-keystore.jks` を Base64 にしたもの |
| `RELEASE_STORE_PASSWORD` | キーストアのパスワード |
| `RELEASE_KEY_ALIAS` | 鍵のエイリアス |
| `RELEASE_KEY_PASSWORD` | 鍵のパスワード |

### 2. ワークフローの権限を確認する

リポジトリの **Settings → Actions → General** で、次を確認します。

- Actions が有効になっていること
- **Workflow permissions** がリリースの作成を妨げていないこと（ワークフロー側で `contents: write` を指定しています）

## ワークフローの動作

`.github/workflows/release.yml` は `v*` に一致するタグの push で実行され、次の順に処理します。

1. JDK 17 と Gradle をセットアップする
2. タグ名と `versionName` が一致するか確認する
3. Secrets からキーストアを一時ディレクトリに復元する。Secrets が 1 つでも欠けていれば停止する（新しい鍵が自動生成されて、更新できない APK ができるのを防ぐため）
4. `./gradlew :app:testReleaseUnitTest buildReleaseApk` でテストと APK のビルドを行う
5. `gh release create` でリリースを作り、次のファイルを添付する
   - `university-attendance-v<versionName>-b<versionCode>-release.apk`
   - 同じ名前の `.sha256`（SHA-256 チェックサム）
6. 一時ディレクトリのキーストアを削除する

## ローカルでのリリースビルド

リリース用の Gradle タスクは `release` グループにまとめてあり、Android Studio の Gradle ツールウィンドウ（`Tasks` → `release`）またはターミナルから実行できます。

| タスク | 内容 |
| :--- | :--- |
| `buildReleaseApk` | 署名済み APK をビルドし、`release/` に出力する |
| `buildReleaseBundle` | 署名済み AAB をビルドし、`release/` に出力する（Google Play 向け） |
| `packageReleaseDistribution` | 単体テストを実行してから、APK と AAB の両方を作る |
| `installReleaseApk` | 署名済み APK をビルドし、USB 接続中の端末に `adb install -r` で入れる |
| `generateReleaseKeystore` | 署名鍵を確認し、なければ自動生成する |
| `cleanReleaseDistribution` | `release/` フォルダを削除する |

```bash
./gradlew :app:testReleaseUnitTest buildReleaseApk
```

`release/` に出力されるファイル:

| ファイル | 内容 |
| :--- | :--- |
| `university-attendance-v<versionName>-b<versionCode>-release.apk` | 配布用 APK |
| `university-attendance-latest-release.apk` | 同じ APK の別名（最新版） |
| `*.apk.sha256` | SHA-256 チェックサム |
| `release-info.txt` | バージョン、署名に使った鍵、サイズ、SHA-256、`adb install` コマンド |

R8 によるコード圧縮を有効にする場合は `-PreleaseMinify=true` を付けます（既定は無効）。有効にした場合は、配布前に実機で全機能を確認してください。

## 手動で GitHub Releases に公開する

GitHub Actions を使わずに公開する場合の手順です。

```bash
./gradlew :app:testReleaseUnitTest buildReleaseApk

git tag v1.1.0
git push origin v1.1.0

gh release create v1.1.0 \
  release/university-attendance-v1.1.0-b2-release.apk \
  release/university-attendance-v1.1.0-b2-release.apk.sha256 \
  --title "v1.1.0" \
  --generate-notes \
  -R Nagatani/university-attendance-app
```

タグを push すると GitHub Actions も動くため、Actions が設定済みの場合は「リリースがすでにある」というエラーで失敗します。どちらか一方の方法に統一してください。

## うまくいかないとき

| 症状 | 原因と対処 |
| :--- | :--- |
| 「タグ (vX) と versionName (vY) が一致しません」 | `versionName` を上げ忘れています。下記の手順でタグを付け直してください |
| 「Secret XXX が設定されていません」 | [Secrets を登録する](#1-secrets-を登録する) を行ってください |
| 署名でビルドが失敗する（`Keystore was tampered with, or password was incorrect` など） | Secrets のパスワードかエイリアスが間違っています。登録し直してください |
| `gh release create` が 403 で失敗する | Workflow permissions を確認してください |
| `release ... already exists` | 同じタグのリリースがすでにあります。下記の手順で削除してから付け直してください |
| 配布した APK で上書き更新できない | 署名鍵が違うか、`versionCode` が増えていません。[鍵の指紋を確かめる](#鍵の指紋を確かめる) を参照してください |

### タグを付け直す

失敗したリリースとタグを削除し、修正をコミットしてからもう一度タグを push します。

```bash
gh release delete v1.1.0 --cleanup-tag -y -R Nagatani/university-attendance-app   # リリースがある場合（リモートのタグも削除）
git push origin :refs/tags/v1.1.0                                                  # リリースがなくタグだけ残っている場合
git tag -d v1.1.0                                                                  # ローカルのタグを削除

# 修正をコミットしてから
git tag v1.1.0
git push origin main v1.1.0
```

公開済みで利用者がすでにダウンロードしたバージョンは、付け直さずに新しいバージョン番号でリリースしてください。
