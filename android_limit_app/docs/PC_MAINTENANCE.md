# PCから30分だけインストールを許可する手順

対象：FocusDPC **0.1.3-beta／versionCode 4**。Windows、USB接続、許可済みのUSBデバッグを使用します。

## 動作

- `pc-tools/AllowInstall.cmd` をダブルクリックすると、インストール禁止だけを一時解除します。
- Device Owner、Private DNSの設定値、DNS変更禁止、FocusDPC自身のアンインストール禁止は維持します。
- **30分後、スマホ側でインストール禁止へ戻します。PCを抜いたり、PCを終了したりしても期限管理はスマホで続きます。**
- スマホの再起動時は、一時許可を終了して禁止へ戻します。プロセスの終了・本体の上書き更新では元の期限を引き継ぎます。
- 許可中の再実行では期限を延長しません。端末の日時変更にも影響されない経過時間を使います。
- スマホ画面の操作は「ポリシーを適用」「状態を更新」の2つのままです。「ポリシーを適用」は一時許可も終了します。
- **許可中は新規インストールと更新の両方が可能です。** Playストアを隠す変更や、更新だけを選別する変更は含みません。

解除時間は `InstallMaintenanceController.kt` 内の `30L * 60L * 1000L` に固定しています。設定ファイル、PCツールの引数、Android側の引数で変更する入口はありません。

## 初回：0.1.1-betaから本体を更新する

0.1.1-betaにはPCツールの受け口がありません。また、禁止中はADBでの上書き更新も制限されます。**初回だけ、Android Studioのデバッガで一時解除してから更新します。Ownerの解除・再登録は不要です。**

1. スマホを1台だけUSB接続し、スマホでFocusDPCを開きます。USBデバッグの接続許可を確認します。
2. **まだ0.1.1-betaのソースを開いている状態で**、Android Studioの `PolicyManager.kt` の `readStatus()` にある次の行へブレークポイント（赤い丸）を付けます。

   ```kotlin
   val errors = mutableListOf<String>()
   ```

3. **Run → Attach Debugger to Android Process** から `io.github.focusdpc` に接続します。通常のRun／Debugによる再インストールは使用しません。
4. スマホの「状態を更新」を押します。PCで処理が止まったら、Alt＋F8（Evaluate Expression）から次を実行します。

   ```kotlin
   devicePolicyManager.clearUserRestriction(
       admin,
       android.os.UserManager.DISALLOW_INSTALL_APPS
   )
   ```

5. 評価画面を閉じ、ブレークポイントを外し、F9で再開します。「アプリインストール禁止」が「無効」になったことを確認します。
6. PCのソースを `git pull` で更新します。自分のNextDNSホスト名を指定した `PolicyConfig.kt` は維持してください。この更新では同ファイルは変更していません。
7. Android Studioのターミナルを、`gradlew.bat` のある `android_limit_app` フォルダーで開きます。Java 17を使い、次を実行します。

   ```powershell
   .\gradlew.bat assembleDebug
   adb install -t -r app\build\outputs\apk\debug\app-debug.apk
   ```

8. FocusDPCを開き「ポリシーを適用」→「状態を更新」を押します。以前のDNS設定と各制限を確認します。

**インストール済みAPKを作った同じPC・同じ署名鍵でビルドしてください。** クラウド側のデバッグAPKは署名が異なるため、そのまま上書きする用途には使いません。

既に新しいソースを取得していてブレークポイントが合わない場合は、変更していない `MainActivity.kt` の `onResume()` 内の `tasks.refresh()` で停止し、代わりに次の式を評価できます。

```kotlin
requireNotNull(getSystemService(android.app.admin.DevicePolicyManager::class.java))
    .clearUserRestriction(
        android.content.ComponentName(this, io.github.focusdpc.MyDeviceAdminReceiver::class.java),
        android.os.UserManager.DISALLOW_INSTALL_APPS
    )
```

この場合は、接続後にスマホでホーム画面へ戻ってからFocusDPCを開き直して停止させます。

## 2回目以降の操作

1. USB接続するスマホを1台にします。初めてのPCではスマホのUSBデバッグ接続許可が必要です。
2. エクスプローラーで `android_limit_app/pc-tools` を開きます。
3. `AllowInstall.cmd` をダブルクリックします。
4. 表示されたUSB端末番号を確認します。`OPENED` に相当する開始案内と残り秒数が出れば許可開始です。
5. PlayストアまたはADBで必要な追加・更新を行います。
6. 30分で自動的に禁止へ戻ります。早く終了したい場合は `BlockInstall.cmd` を実行します。

| ファイル | 用途 |
| --- | --- |
| `AllowInstall.cmd` | 一時許可の開始。既に許可中なら残り時間だけ表示 |
| `MaintenanceStatus.cmd` | 状態と、確認時点の残り秒数を表示 |
| `BlockInstall.cmd` | 直ちにインストール禁止へ戻す |
| `FocusDpcMaintenance.ps1` | 上の3ファイルから呼ぶ共通処理。時間指定の引数はありません |

ADBはPATH、または `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` から探します。表示はWindows PowerShellの文字コード差を避けるため英語です。`.cmd` はPC内の同梱スクリプトだけを起動します。

## エラーの見方

| 表示 | 対応 |
| --- | --- |
| `Connect exactly one USB phone...` | USB接続台数・ケーブル・USBデバッグの許可を確認します。複数端末のうち1台を勝手に選びません |
| `Install version 0.1.3-beta or later` | 本体の更新と、接続端末を確認します。初回は上記の更新手順が必要です |
| `NOT_OWNER` | FocusDPCのDevice Owner登録状態を確認します |
| `NOT_BLOCKED` | 時間制限のない解除状態です。先にFocusDPCで「ポリシーを適用」を押します |
| `FAILED_BLOCKED` | 解除に失敗し、禁止へ戻した状態です。端末・時刻予約の権限を確認します |
| `RESTORE_FAILED` | 禁止への復帰を確認できませんでした。PCをつないだまま `BlockInstall.cmd` と状態確認を行います |

Android 12／12Lでは、正確なアラーム権限が必要になる場合があります。必要な権限が使えなければ解除は開始しません。許可済みPCから `adb shell appops set io.github.focusdpc SCHEDULE_EXACT_ALARM allow` で許可した後、再試行します。Android 13以上では同梱の `USE_EXACT_ALARM` 権限を使います。OS・メーカーによる挙動はテスト用実機で確認してください。

## 実装上の保護と確認事項

- 解除命令はContentProviderで受け、DUMP権限と実際の呼出元UID（ADB shellの2000）を確認します。通常アプリはDUMP権限だけを持っていても実行できません。
- PC認証はAndroidのADB接続許可とPCのADB鍵を使用します。スマホ画面にパスワードや解除ボタンは設けません。別の解除APKを追加インストールする方式ではありません。
- 予約と期限の保存が成功してから禁止を解除します。再制限は実際の状態を確認してから期限記録と予約を削除します。
- 保存は画面ロック解除前も読める端末保護ストレージを使い、再起動時にも復帰処理を実行します。記録が不正なら禁止へ戻します。
- 30分後の処理にはスリープ対応のAndroidアラームを使います。OSの実行遅延があり得るため秒単位の無遅延を保証するものではありません。期限前に開始済みのインストール処理は強制中断しません。
- β版のADBによるOwner解除・デバッガ接続の復旧経路は従来どおりです。端末所有者による全ての開発操作を防ぐ仕組みではありません。
- 本体の上書き更新・画面ロック・USB取り外し・30分経過・再起動をテスト用実機で確認してから本番運用してください。

参考：[ContentProviderの権限制御](https://developer.android.com/guide/topics/providers/content-provider-creating#Permissions)／[Androidアラーム](https://developer.android.com/develop/background-work/services/alarms)／[DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
