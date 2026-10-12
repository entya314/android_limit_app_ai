# FocusDPC β版の検証結果

検証日：2026-10-12。元の `READ_ME.txt` は変更していません。

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

7種類のテストシナリオを、再確認・回復処理を含めて9回実行し、すべて成功しました。テスト終了時にDevice Ownerと制限は解除済みです。テストAPKはアンインストールしています。

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
