# LINE内部ブラウザ制限

対象は `io.github.focusdpc`（FocusDPC）です。現在のソースはPrivate DNSとインストール制限を管理するDPCで、独自WebViewブラウザは含まれていません。このプロジェクトへ追加し、既存のポリシー・PC一時許可・SDK設定は変更していません。

## 判定と安全側の動作

- `TYPE_WINDOW_STATE_CHANGED` のうち `jp.naver.line.android` だけをXMLとコードで対象にします。
- classNameが `com.linecorp.line.iab.browser.impl.InAppBrowserActivity` に完全一致するときだけBACKの候補にします。汎用View、WebView、URLらしい文字列、未知のActivityからの推測はしません。
- 通知されたwindowIdが現在のactiveかつfocusedなアプリウィンドウと一致すること、イベントが500ms以内で時刻順序も正しいことを確認します。確認できなければBACKしません。
- 遅延タスクやポーリングは使わず、イベント受信時に `GLOBAL_ACTION_BACK` を最大1回実行します。XMLのnotificationTimeoutは0msです。
- 重複イベントやBACK失敗時も自動リトライしません。時間経過だけでは次のBACKを許可しません。
- `jp.naver.line.android.activity.chathistory.ChatHistoryActivity` または `jp.naver.line.android.activity.main.MainActivity` への復帰を、新しいイベントと現在のウィンドウで確認した場合だけ、次回の起動を制限できます。Dialog・未知の画面・他アプリ・通話画面では再許可しません。

`getWindows()`による現在ウィンドウの照合には `flagRetrieveInteractiveWindows` と `canRetrieveWindowContent=true` が必要です。Androidには画面内容へアクセスできるサービスとして表示されますが、実装が参照するのはウィンドウのID・種類・active/focusedだけです。`event.source`、`rootInActiveWindow`、ノードの本文・URL・メッセージ、イベントのtext/contentDescriptionは読みません。新たなuses-permission、外部通信、ファイルやDBへの監視情報保存は追加していません。BIND_ACCESSIBILITY_SERVICEはシステムだけがサービスへ接続できるようにする宣言です。

確認とBACKはAndroidの別処理なので、確認直後の画面切替との競合を完全に排除するAPIではありません。時刻・現在ウィンドウの照合と単発操作で危険を減らしています。高負荷で500ms以上遅延した場合や通常画面への復帰イベントが欠落した場合は、誤操作防止を優先して制限を見送ります。再許可されない場合は、トークまたはメイン画面を開き直してから試してください。

## ビルドとインストール

Android Studioで `android_limit_app` を開き、既存と同じ署名鍵、JDK 17、SDK 36を使います。NextDNSの設定値を維持してください。

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
adb devices
adb -s SERIAL install -t -r app\build\outputs\apk\debug\app-debug.apk
adb -s SERIAL shell am start -n io.github.focusdpc/.MainActivity
```

macOS / Linux:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
adb devices
adb -s SERIAL install -t -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -n io.github.focusdpc/.MainActivity
```

`SERIAL`は対象の端末番号へ置き換えます。既存のtestOnly設定を維持しているため `-t` が必要です。インストール禁止中は先に既存の `pc-tools/AllowInstall.cmd` で一時許可し、更新後に `BlockInstall.cmd` で戻します。旧版からの初回更新は [PC一時許可の手順](PC_MAINTENANCE.md) を参照してください。

署名が異なるAPKは上書きできません。クラウドのデバッグAPKは普段のPCと署名が異なる可能性があるので、その場合は普段のPCで同じ鍵を使ってソースをビルドしてください。今回の追加にDevice Ownerの解除・再登録や端末初期化は不要です。

## 手動で有効化・無効化

1. FocusDPCの「LINE内部ブラウザ制限」から「ユーザー補助の設定を開く」を押します。
2. Android設定の「ダウンロードしたアプリ」「インストール済みアプリ」等から「LINE内部ブラウザ制限」を選び、説明とAndroidの確認画面を読んで有効にします。名称はOS・メーカーによって異なります。
3. FocusDPCへ戻り「Android設定：有効」を確認します。これは設定上の状態で、サービス接続や遮断成功の保証ではありません。
4. LINEトーク内URLで動作を確認します。無効化も同じAndroid設定で行えます。無効化しても既存のDPC機能は動作します。

Device Owner登録とは独立しており、このサービスだけの利用にはDevice Ownerは不要です。「制限付き設定」などで操作できない場合は、本人がインストールしたアプリであることを確認した上で、Androidのアプリ情報メニューに「制限付き設定を許可」があるか確認してください。ADBによる強制有効化は行いません。

## デバッグログ

デバッグ可能なビルドだけ `LineBrowserBlock` タグで出力します。releaseでは出しません。

```sh
adb -s SERIAL logcat -s LineBrowserBlock:D "*:S"
```

本文やURLは含まず、LINEのイベント種別・package・class・windowId・経過ms・フォーカス照合・判定・BACK受付結果だけです。アプリ独自の履歴ファイルは作りませんが、Androidのlogcatバッファには残るため必要な検証中に使用してください。

| 判定 | 意味 |
| --- | --- |
| `BACK` | 条件を満たし、単発操作を要求 |
| `BACK accepted=true/false` | Androidの要求受付結果。trueでも実際の終了は画面で確認が必要 |
| `NOT_BROWSER` | className不一致。推測で閉じない |
| `UNCONFIRMED_WINDOW` | 現在の操作対象との一致を確認できない |
| `STALE` | 遅延・同時刻の重複・順序逆転・時刻不正 |
| `DUPLICATE` | 既に試行済み。通常画面への復帰確認待ち |
| `REARMED` | トーク・メイン画面への復帰を確認 |

トーク・通話・内部ブラウザをそれぞれ開いて比較します。ADBのdumpsysにActivity名が出ることと、AccessibilityEventのclassNameに同じ名前が出ることは別です。内部ブラウザで汎用View名しか出ない場合、この版は閉じません。

代替案は、実機で確認したブラウザ固有のresource-idと複数の構造条件を照合し、専用の閉じるボタンを操作する方式です。ただし現時点では実機の根拠がなく実装していません。WebViewの存在、URLらしい文字列、「閉じる」という文言、LINEパッケージ名だけで判断すると認証・通常操作を誤検出するため採用しません。LINE更新時も、検証してから対象を追加する必要があります。

## 実機テスト：すべて未実施

この環境からarrows We2と実際のLINEに接続できないため、次は実機で確認してください。

| 操作 | 期待結果 |
| --- | --- |
| 通常トーク・メイン画面を開く | 正常動作し、BACKしない |
| メッセージ送受信・通知からトークへ移動 | 正常動作し、BACKしない |
| 音声通話の発信・着信・通話中・終了 | 正常動作し、BACKしない |
| トーク内URLをタップ | 指定Activityを検出し閉じてトークへ戻る |
| 内部ブラウザを短い間隔で繰り返し開く | 毎回1回だけBACKし、トークまで閉じない |
| ブラウザを即座に手動で閉じる／別アプリへ切り替える | 古いイベントでトークや別アプリを閉じない |
| 起動直後の着信・キーボード・Dialog・画面回転 | 不明な画面や通話へBACKしない。見送り時のログを確認 |
| 他アプリを開く | 影響なし |
| 既存の制限機能・制限ブラウザを使う | 従来どおり。DNS・インストール制限等の設定も維持 |
| サービスを無効にして既存アプリを使う | 正常動作。LINE用BACKなし |
| Android設定で切り替えてFocusDPCへ戻る | 有効・無効の表示が追従 |
| 再起動・本体更新後 | サービス状態と実際の制限、既存機能を再確認 |

## クラウドでの検証結果（2026-10-26）

JDK 17.0.16、Gradle 8.13（Wrapper指定と同じ版）、SDK 36 / Build Tools 36.0.0で以下を実行しました。クラウドでは導入済みのGradleを使っています。

```sh
gradle --offline testDebugUnitTest assembleDebug assembleRelease lintDebug
```

- デバッグAPKと未署名リリースAPKのビルド成功。
- 既存のInstallMaintenanceControllerTest 17件、追加のLineBrowserBackGuardTest 9件、計26件成功。
- Lintはエラー0件・警告0件。差分の空白チェックも成功。
- 追加テストは完全一致、通話等の未知画面、別アプリ、重複、失敗時の再試行抑止、即時再オープン、古い・逆順・未来のイベント、現在ウィンドウ未確認、誤った復帰イベントを検証。
- デバッグAPKのuses-permissionが既存4件のままであることと、APK署名を確認。
- 実機・実LINE・エミュレータ上の統合テストは未実施。Androidが通知する実際のclassNameや、実際のフォーカス遷移は上記の単体テストでは検証できません。

アプリのversionCode/versionName、NextDNS設定、署名設定は変更していません。付属デバッグAPKを上書きできるかは、既存端末の署名との一致が必要です。

## 制限範囲と残る経路

対象は指定のLINE内部ブラウザActivityだけです。起動や初回描画、通信開始を阻止するものではなく、表示後に戻る操作を要求します。表示時間の上限や内容が一瞬も見えないことは保証できません。BACKがページ内履歴として扱われる場合など、1回で終了しない可能性も実機確認が必要です。連続BACKによる強制終了にはしていません。

外部ブラウザ、Custom Tabs、他のLINE Activity、別アプリ・ディープリンクは対象外です。認証・ヘルプ等が同じ対象Activityを使う場合はそれらも閉じられます。サービスの無効化・停止、イベント欠落、className変更、復帰未確認時も制限できません。認証等でブラウザを使うLINE機能への影響も確認してください。この追加だけで全Web閲覧経路を遮断するものではありません。

## 変更ファイル

| ファイル | 内容 |
| --- | --- |
| `app/src/main/java/io/github/focusdpc/LineBrowserAccessibilityService.kt` | イベント受信、現在ウィンドウ照合、単発BACK、ログ |
| `app/src/main/java/io/github/focusdpc/LineBrowserBackGuard.kt` | 完全一致、時刻・順序、重複抑止と再許可 |
| `app/src/main/res/xml/line_browser_accessibility_service.xml` | イベント・パッケージ・必要な能力の限定 |
| `app/src/main/AndroidManifest.xml` | サービス登録 |
| `app/src/main/java/io/github/focusdpc/MainActivity.kt` | 設定状態取得と設定への遷移 |
| `app/src/main/res/layout/activity_main.xml` | 既存画面に状態とボタン追加 |
| `app/src/main/res/values/strings.xml` | 日本語の説明・状態表示 |
| `app/src/test/java/io/github/focusdpc/LineBrowserBackGuardTest.kt` | 誤操作防止と繰り返し起動の単体テスト |
| `docs/LINE_BROWSER_BLOCK.md` | この説明と確認項目 |
| `README.md` | 追加機能への案内と既存説明の補足 |
