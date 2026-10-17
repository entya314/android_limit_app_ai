# FocusDPC β版の検証結果

初版の検証日：2026-10-12。以下は0.1.0-betaの検証記録です。各版の更新確認は後半に記載しています。元の `READ_ME.txt` は変更していません。

## ビルド

- JDK 17.0.16、Gradle 8.13、Android Gradle Plugin 8.13.2、Kotlin 2.2.21。
- compileSdk / targetSdk：36、minSdk：29、Build Tools：36.0.0。
- `./gradlew --offline assembleDebug assembleDebugAndroidTest lintDebug --warning-mode all`：成功。
- Kotlinのコンパイラ警告をエラーとして扱う設定でビルド成功。
- Android Lint：**エラー0件・警告0件**。
- `apksigner verify --verbose`：APKの署名検証に成功（v2署名）。
- `aapt2 dump badging`：minSdk 29、targetSdk 36、`testOnly=true`、`INTERNET`権限を確認。

SDKのメタデータ形式（XML版）についてツールチェーンの警告が出ますが、ビルド・署名検証・Lint・実行テストは成功しています。

APK：`app/build/outputs/apk/debug/app-debug.apk`。インストールには `adb install -t` が必要です。

## Device Ownerとポリシーの実行テスト

Android 10 / API 29、x86_64の新規テスト専用AVDで、標準Instrumentationを使用しました。ソフトウェア実行（KVMなし）で検証しています。実機ではポリシーを変更していません。

`python3 scripts/emulator_smoke_test.py --serial emulator-5556`：**成功**。

| 確認内容 | 結果 |
| --- | --- |
| ホスト名の検証：プレースホルダー、URL、空文字、空白、ラベル長など | 合格 |
| DNS接続確認に必要なINTERNET権限が付与されていること | 合格 |
| Owner未登録時の適用・解除で指定のメッセージを返すこと | 合格 |
| ADBでDevice Ownerに登録できること | 合格 |
| 未設定のNextDNSを固定せず、他の2つの制限を適用できること | 合格 |
| 再適用しても結果・制限が正常であること | 合格 |
| ADB禁止と工場出荷状態へのリセット禁止を追加していないこと | 合格 |
| 到達不能なホストで、DNS APIのHOST_NOT_SERVINGエラーを処理できること | 合格 |
| DNS接続失敗時にDNS変更禁止を新たに有効にしないこと | 合格 |
| 制限解除とPrivate DNSの「自動」への変更 | 合格 |
| 制限解除後もDevice Owner登録が残ること | 合格 |
| ADBのremove-active-adminでOwnerを解除できること | 合格 |
| Owner解除後、未登録状態を再取得できること | 合格 |

6種類のテストシナリオを、再確認・回復処理を含めて9回実行し、すべて成功しました。テスト終了時にDevice Ownerと制限は解除済みです。テストAPKはアンインストールしています。

## 画面の確認

実際の画面をADBとUI Automatorで操作し、以下を確認しました。

- 起動時のDevice Owner未登録状態の表示。
- 「ポリシーを適用」を押した後の「このアプリはDevice Ownerではありません」の表示。
- 画面回転後の状態表示。
- アプリの再起動後の状態取得。

エミュレータのSystem UIで応答待ちダイアログが出たため、ダイアログを閉じ、テスト用AVDのアニメーションを停止して画面確認を完了しました。FocusDPC本体のクラッシュは確認していません。

## 今回確認していないこと

- 実際のNextDNS構成IDへのDNS over TLS接続成功とフィルタリング動作。初期値は仕様どおり `YOUR_ID.dns.nextdns.io` のままです。
- arrows We2実機での登録・動作・解除。
- API 30以降の端末での実行と、実機の各メーカーの設定画面。

実際のNextDNS IDに変更した後のテスト、通常のインストール画面での制限確認、実機での検証は `README.md` の手順に従って行ってください。

## 0.1.1-beta：画面の解除ボタン削除

確認日：2026-10-17。versionCodeは2です。

- 解除ボタン、確認ダイアログ、関連する画面文言を削除しました。
- PolicyTaskRunnerの解除メソッドを削除し、Activityから解除する呼出し経路をなくしました。
- PolicyManagerの解除メソッドは、エミュレータ自動テストの後片付け用に保持しています。
- `./gradlew --offline assembleDebug assembleDebugAndroidTest lintDebug --warning-mode all`：成功。
- Android Lint：**エラー0件・警告0件**。
- 生成APKのレイアウトをaapt2で確認し、Buttonが2つであることを確認しました。
- APKのリソースに解除ボタン・ダイアログのIDや文言がないことを確認しました。
- aapt2でversionCode 2、versionName 0.1.1-beta、minSdk 29、targetSdk 36、testOnly=true、INTERNET権限を確認しました。
- `apksigner verify`：成功。
- 手順書・設計書とREADMEを更新し、文書内リンクを確認しました。

今回の更新ではテストAPKのビルドまで確認し、エミュレータと実機での実行テストは再実施していません。上記の初版の実行結果を、新版の実行結果として扱うものではありません。実機では同じ署名でAPKを上書きし、解除ボタンが表示されないことと、適用・状態更新・既存ポリシーの維持を確認してください。

画面の解除を削除した版ですが、ADBの `remove-active-admin` と出荷時リセットによる復旧は可能です。実機の復旧ではOwner解除後にPrivate DNS設定が残る場合があるため、Android設定画面で「自動」などへ戻します。

## 0.1.2-beta：ADB許可・Playストア禁止

確認日：2026-10-17。versionCodeは3です。

### 実装した変更

- Playストアを `setApplicationHidden()` で非表示・利用不可にします。閲覧・自動更新も停止します。
- 提供元不明アプリのグローバル禁止で、スマホ上で開く通常APKを制限します。ADBはこの禁止の対象外です。
- 上記2項目の成功を確認した場合だけ、旧版の `DISALLOW_INSTALL_APPS` を解除します。ADBと通常操作を一括で禁止する設定は新しく追加しません。
- 画面は7項目の状態表示と2つの操作ボタンです。解除ボタンはありません。
- `PolicyConfig.kt` は変更していないため、NextDNSの個人設定を書いたファイルを上書きする変更を含みません。

### 最終ビルドで確認できたこと

- `./gradlew --offline assembleDebug assembleDebugAndroidTest lintDebug --warning-mode all`：成功。
- Kotlinの警告をエラーとして扱う設定で、アプリとInstrumentationの両方がコンパイル成功。
- Android Lint：**エラー0件・警告0件**。
- 生成APKはversionCode 3／0.1.2-beta、SDK 29〜36、testOnly=true、Playストアへのqueries宣言を確認。
- APK内のレイアウトはButton 2つ。解除ボタン・ダイアログのリソースがないことを確認。
- `apksigner verify`：成功。
- 手順書・設計書・更新ガイドのMarkdownと相対リンクを確認。
- 自動テストスクリプトのPython構文を確認。

### 実行テストの結果と制約

**新版の自動実行テストは完走していません。** 以前の版の合格結果を、新版の動作確認済みという意味では使用しません。

Android 10／API 29のソフトウェアエミュレータで実行を試みました。先行AVDではOSの再起動とPackage Managerの `Broken pipe` が発生しました。アプリ・テストAPKの導入と、先行ビルドでの `hostname_validation`・`non_owner` は通過しましたが、その後のストア代役APK導入が180秒でタイムアウトしました。新規の480×800／メモリ2048MBのAVDでも、起動後のsettingsサービスが切断されました。

いずれも新しいポリシーを適用する前の段階で止まっています。したがって、このクラウド環境では以下を実行確認できていません。

- Playストア相当パッケージの非表示・再表示と起動拒否。
- ポリシー適用後のADB新規導入、上書き、FocusDPC自身の更新。
- 旧版の一括禁止からの移行、全解除、再適用の一連の動作。
- 本物のGoogle Playストアの動作、実機とAPI 30以降での動作。

自動スクリプトには上記を検証する7種類のシナリオと、実際のADB導入・更新、代役APKの表示確認を用意しています。AOSPで代役を使う場合はInstrumentationを12回実行する構成です。現時点では「用意済み」であり「全件合格」ではありません。

まずテスト用スマホで[初回更新と確認手順](docs/UPGRADE_012.md)を実施し、7項目の表示、Playストア利用不可、通常APK禁止、ADBでの追加・更新を確認してください。旧版の禁止が有効な間は新版APKも入れられないため、Owner登録を外さずにインストール禁止だけ一時解除する手順を使います。
