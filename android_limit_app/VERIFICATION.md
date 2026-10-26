# FocusDPC β版の検証結果

初版の検証日：2026-10-12。以下は0.1.0-betaの検証記録です。0.1.1-betaと0.1.3-betaの更新確認は末尾に記載しています。元の `READ_ME.txt` は変更していません。

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

## 0.1.3-beta：PCからの30分一時許可

確認日：2026-10-26。versionCode 4。0.1.2-betaは取り消し済みのため、そのPlayストア非表示などの変更は含みません。

### ビルド・静的検証

- JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、compile／target SDK 36、min SDK 29。
- `./gradlew --offline testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --warning-mode all`：最終ソースで成功。
- Android Lint：エラー0件・警告0件。コンパイラ警告をエラーとして扱う設定でも成功。
- APKのversionName／versionCode、testOnly、必要なアラーム・起動通知権限とv2署名を確認。
- 本体の時間はソースの `30L * 60L * 1000L` に固定。PCやProviderに時間指定の引数はありません。
- 画面の解除ボタンは追加せず、通常操作は従来の2ボタンを維持。PolicyConfigのNextDNS定数は変更していません。

### 単体テスト：17件成功

実際の30分待機ではなく、Android非依存のControllerにテスト用の時計・保存・管理APIを与えて検証しました。

- 保存と予約が成功してから解除する順序、30分の固定期限。
- 許可中の再要求・プロセス再生成で期限を延長しないこと。
- 期限直前・期限ちょうど・期限後の動作。
- 再起動時の早期終了、起動回数の変更、経過時間の巻き戻り。
- Ownerなし・元の禁止なし・起動回数不明の場合の拒否。
- 未設定端末の状態確認で新たな制限を課さないこと。
- 保存失敗・開始時と許可中のアラーム予約失敗・解除APIが変更反映後に失敗した場合の復帰。
- 復帰APIの失敗と再試行、戻り値だけを信用せず実効値を確認すること。
- 不正／長すぎる期限記録、手動再適用後の重複通知。

### PCツール：12ケース成功

Microsoft公式配布元からチェックサムを照合して取得したPowerShell 7.6.9をLinux上で使用しました。偽ADBとの実プロセス通信による検証であり、Windows PCとUSBスマホでの実行結果ではありません。

```sh
python3 scripts/test_pc_maintenance.py --pwsh /path/to/pwsh
```

- 開始、既に許可中、状態確認、即時終了。
- 複数USB端末の場合に変更コマンドを送らないこと。
- Providerなし、不正応答、ADBの失敗、復帰失敗、Ownerなし、未知の結果コードを成功扱いしないこと。
- 時間指定の引数を拒否すること。

### 実行テストの限界

今回専用のAPI 29 AVD（`focusdpc-maintenance013`、KVMなし）を作成して起動しましたが、起動完了を確認できませんでした。`emulator_smoke_test.py --serial emulator-5560` は起動状態の事前確認で終了しました。アプリのインストール・Owner登録・制限変更に進んでいません。今回起動したエミュレータは停止しました。

したがって、**実端末上のProviderへのADB呼出し、実際の管理APIの切替、OSのアラームによる30分後の自動復帰、スリープ・再起動・本体更新時の復帰は、今回の実行では未確認です。** テストAPKとスクリプトには、通常アプリからの入口拒否、Owner・DNS維持、ADB上書き更新、再制限後のインストール拒否を確認する項目を追加しています。

テスト用スマホで[PC一時許可手順](docs/PC_MAINTENANCE.md)に沿って確認してください。以前の版の実行成功を今回の成功記録として扱わないよう、上記の単体テスト・PC模擬テストと区別しています。

## PCツール修正：日本語版Windows PowerShellの文字コード

確認日：2026-10-26。スマホ側は0.1.3-betaを継続使用します。

- 利用者の環境で `FocusDpcMaintenance.ps1` の34行目に `UnexpectedToken` が発生しました。
- BOMなしUTF-8の旧スクリプトを、日本語版Windows PowerShell 5.1の既定文字コードCP932として読み込むと、同じ34行目の構文エラーが再現しました。UTF-8としての解析にはエラーがありませんでした。
- 日本語コメントの誤読を防ぐため、スクリプトをUTF-8 BOM付きで保存しました。処理内容とAPKは変更していません。
- 既定CP932＋BOM自動判定のStreamReaderから読み、PowerShellのパーサーに渡す回帰テストを追加しました。UTF-8 BOMの保持も検査します。
- 文字コード検証を含め、PCツールの模擬テスト13ケースが成功しました。PowerShell 7.6.9／Linux上での検証です。Windows PowerShell 5.1本体での実行は、この環境では確認していません。
- 修正版取得後は `AllowInstall.cmd` を再実行します。この修正のためのAPK再ビルドや再インストールは不要です。
