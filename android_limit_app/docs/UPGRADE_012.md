# 0.1.2-betaへの更新とADBでのアプリ追加

対象：Windows／Android Studio／FocusDPCのdebug APK。作成日：2026-10-17。

旧版の一括インストール禁止を、新しい「Playストア禁止・通常APK禁止・ADB許可」へ切り替える手順です。Device Owner登録を残して更新します。画面の解除ボタンは復活させません。

ビルド・Lintは成功していますが、クラウドAVDのOS不調で新版の実行テストは未完走です。[検証結果](../VERIFICATION.md#012-betaadb許可playストア禁止)を確認し、まずテスト用スマホで以下を試してください。

## 1. 新しい仕様

| 操作 | 適用後 |
| --- | --- |
| PCからADBで新規APKを入れる | 許可 |
| PCからADBで同じ署名のAPKを更新する | 許可。FocusDPC自身の更新も対象 |
| Playストアから入れる | Playストアを非表示・利用不可にする |
| Playストアで閲覧・既存アプリを自動更新する | Playストアの利用停止により使えなくなる |
| ブラウザーやファイル管理から通常APKを開いて入れる | 提供元不明アプリの禁止で制限 |
| 既に入っている一般アプリを使う | 引き続き利用可能 |
| スマホのFocusDPC画面から制限を解除する | 解除操作なし |

Androidが信頼する別のストアなどを、すべて自動検出して禁止する機能ではありません。今回のストア対象は `com.android.vending`（Google Playストア）です。

## 2. 旧版から初めて更新する

### 2.1 なぜ初回だけ一時解除が必要か

0.1.0／0.1.1の `DISALLOW_INSTALL_APPS` は、通常操作に加えてADBのインストールも止めます。その制限が有効なままでは、新しいFocusDPC自体も入れられません。

既に旧版のインストール禁止を一時解除済み、またはOwner登録のみでまだポリシー未適用なら、次の一時解除は不要です。「2.3」へ進みます。

**`dpm remove-active-admin` は今回の更新には使いません。** Ownerを外すと、Googleアカウント等がある端末で再登録できなくなる場合があります。

### 2.2 PCからインストール禁止だけを一時解除する

現在のインストール済みアプリはdebug APKなので、Android Studioから実行中プロセスへデバッガーを接続できます。スマホ1台をUSB接続し、USBデバッグを許可して進めます。

1. Android StudioでFocusDPCのプロジェクトを開く。
2. スマホでFocusDPCを開く。
3. `MainActivity.kt` の `onResume()` 内にある `tasks.refresh()` の行の左側をクリックし、赤い丸（ブレークポイント）を付ける。
4. メニューの「Run → Attach debugger to Android process」で `io.github.focusdpc` を選ぶ。通常のRun／DebugボタンでAPKを入れ直す操作はしない。
5. スマホでホームへ戻り、もう一度FocusDPCを開く。Android Studioで処理が停止する。
6. 停止位置が `MainActivity.onResume` であることを確認し、`Alt + F8`（Evaluate Expression）を開く。Kotlinのコードとして、次を入力して「Evaluate」を押す。複数行入力欄に切り替えられる場合はコード断片として入力する。

~~~kotlin
val dpm = requireNotNull(getSystemService(android.app.admin.DevicePolicyManager::class.java))
val admin = android.content.ComponentName(this, io.github.focusdpc.MyDeviceAdminReceiver::class.java)
dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_APPS)
~~~

7. エラーがないことを確認したら、赤い丸をもう一度クリックして外し、`F9`で処理を再開する。
8. 旧版の「アプリインストール禁止」が「無効」、Device Ownerが「有効」のままであることを確認する。

DNSの設定・DNS変更禁止・FocusDPCのアンインストール禁止は、この操作では変更しません。エラーが出た場合は先へ進まず、停止した行とエラー全文を確認してください。

この一時解除中はPlayストア等からのインストールも可能になるため、続けて新版を入れ、新しいポリシーを適用します。

### 2.3 更新を取得し、同じPCで再ビルドする

以前ビルドしたAndroid Studioのターミナルで実行します。

~~~powershell
git pull
~~~

`PolicyConfig.kt` の `NEXTDNS_HOSTNAME` が自分のホスト名になっていることを確認してから、ビルドします。

~~~powershell
java -version
.\gradlew.bat assembleDebug
~~~

Javaは17を使用します。`Unsupported class file major version 69` はJava 25との互換性問題なので、JDK 17へ切り替えてください。詳しくは[作業手順書](SETUP_GUIDE.md#23-javaが認識されない場合)を参照します。

`BUILD SUCCESSFUL` が出たら上書きします。

~~~powershell
adb install -t -r app\build\outputs\apk\debug\app-debug.apk
~~~

同じPCのデバッグ署名を使います。署名違いのエラーになった場合は、Ownerを外して削除する前にエラーを確認してください。

### 2.4 新しいポリシーを適用する

スマホのFocusDPCを開き、「ポリシーを適用」→「状態を更新」を操作します。

| 項目 | 期待値 |
| --- | --- |
| Device Owner | 有効 |
| NextDNS | 設定済み |
| Private DNS変更禁止 | 有効 |
| Playストア利用禁止 | 有効 |
| 提供元不明アプリ禁止 | 有効 |
| ADBインストール許可 | 有効 |
| DPCアンインストール禁止 | 有効 |

Playストアの非表示と通常APK禁止を確認できた場合にだけ、旧版の一括インストール禁止を外します。失敗した項目があれば操作結果を確認してください。「ADBインストール許可」はポリシー上の許可で、USBデバッグやPCの接続許可まで自動で行う表示ではありません。

## 3. agri_appなどをADBで追加する

新版の適用後は、インストール禁止を毎回解除する必要はありません。agri_appのAPKを作成し、次のPowerShellを実行して選択します。

~~~powershell
Add-Type -AssemblyName System.Windows.Forms
$agriApk = New-Object System.Windows.Forms.OpenFileDialog
$agriApk.Filter = "APK (*.apk)|*.apk"
if ($agriApk.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
    adb install -t -r $agriApk.FileName
}
~~~

`Success` を確認してagri_appを開きます。既存アプリの更新には、更新前と同じ署名が必要です。

Playストアが起動できないこと、スマホ上で開く通常APKの導入が制限されること、ADB追加後も7項目の状態が維持されることを実機で確認してください。

## 4. PCから全ポリシーを解除する場合

画面に解除ボタンはありません。Playストアの表示を元に戻す必要がある場合は、Ownerを外す前に、このデバッグ版で次を行います。

1. `PolicyManager.kt` の `readStatus()` 内の `val errors = mutableListOf<String>()` にブレークポイントを付ける。
2. 実行中の `io.github.focusdpc` にデバッガーを接続する。
3. スマホで「状態を更新」を押し、`PolicyManager.readStatus` のワーカースレッドで停止させる。
4. `Alt + F8`で `clearBetaPolicies()` を実行し、返されたstepsが6件あり、すべて `success=true` であることを確認する。
5. ブレークポイントを外して `F9`で再開する。

この処理はDNS変更禁止・通常APK禁止・旧版の一括禁止・自身の削除禁止を解除し、Playストアを再表示してDNSを「自動」へ戻します。以前のDNSの復元ではありません。Owner登録は残ります。

アプリを削除するなら、その後に[作業手順書のOwner解除・削除手順](SETUP_GUIDE.md#9-制限の解除とアプリの削除)を実行します。Ownerだけを先に外すと、OSによってはPlayストア非表示やDNS設定が残る場合があります。既にOwnerを外してしまった場合は、現在の状態を確認してから復旧方法を選んでください。

標準APIの動作は[アプリ非表示](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setApplicationHidden(android.content.ComponentName,%20java.lang.String,%20boolean))と[提供元不明アプリの禁止](https://developer.android.com/reference/android/os/UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)を参照しています。デバッガーの接続方法は[Android Studio公式説明](https://developer.android.com/studio/debug#attach-debugger)で確認できます。
