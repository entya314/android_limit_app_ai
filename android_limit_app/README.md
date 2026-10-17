# FocusDPC β版

自分のAndroidスマートフォンの利用環境を制限する、端末単体で動くDevice Policy Controller（DPC）です。元の要件は `READ_ME.txt` に残しています。企業向けMDMサーバー、アカウント連携、バックグラウンド監視サービスは使用しません。

Kotlin / Android 10（API 29）以上。対象端末は **arrows We2** ですが、まず使い捨てのAndroid Emulatorで動作確認してください。

## 作業手順書・詳細設計書

- [作業手順書](docs/SETUP_GUIDE.md)：Windows／Android Studioでのビルドから、実機の登録・適用・確認・解除まで。今回つまずいた点と対処も記載しています。
- [詳細設計書](docs/DETAIL_DESIGN.md)：クラスの役割、private以外のメソッドの入力・出力・処理、データ構造と非同期処理の説明です。

## 最初に読むところ

1. `app/src/main/java/io/github/focusdpc/PolicyConfig.kt`：NextDNSのホスト名を変更する場所。
2. `PolicyManager.kt`：Device Owner確認、状態取得、適用・解除。日本語コメントに処理の理由を書いています。
3. `MainActivity.kt`：状態表示とボタン操作。
4. `PolicyTaskRunner.kt`：DNS処理を別スレッドで実行し、画面回転時の二重実行を防ぐ部分。

コメントはコードの説明です。不要なコードを大量にコメントアウトして残すことはせず、β版で実行しない制限はコメントで明示しています。

## このβ版の動作

「ポリシーを適用」を押すと、Device Ownerであることを確認して以下を実行します。

| 項目 | 使用する標準API |
| --- | --- |
| NextDNSのPrivate DNS設定 | `setGlobalPrivateDnsModeSpecifiedHost()` |
| Private DNSの設定変更禁止 | `addUserRestriction(DISALLOW_CONFIG_PRIVATE_DNS)` |
| アプリの新規インストール禁止 | `addUserRestriction(DISALLOW_INSTALL_APPS)` |
| FocusDPC自身のアンインストール禁止 | `setUninstallBlocked()` |

**初期値は指定どおり `YOUR_ID.dns.nextdns.io` です。** 自分のホスト名へ変更するまではDNS設定を行いません。DNS設定が失敗した場合も、アプリインストール禁止とFocusDPCのアンインストール禁止は個別に適用し、画面に各項目の結果を表示します。DNS設定が成功し、実際のホスト名も確認できた場合にだけ、Private DNS変更禁止を新たに有効にします。

一括トランザクションではないため、部分的な適用・解除があり得ます。失敗時に既存の制限を勝手に解除することはありません。現在の状態は毎回AndroidのAPIから読み取ります。「設定済み」は対象ホストがPrivate DNSに指定されていることを示し、現在の通信・フィルタリングが成功している保証ではありません。

Device Ownerでないときの適用・解除は、**「このアプリはDevice Ownerではありません」** と表示します。権限が必要なDNS・アンインストール禁止の状態は「未確認」と表示し、無効だと誤表示しません。

このβ版は以下の制限を追加しません。

- ADB・USBデバッグ・開発者向けオプションの禁止。
- 工場出荷状態へのリセット禁止、Factory Reset Protectionの設定。
- Device Ownerの解除を妨げる処理、自動再登録、端末データ消去。

「制限を解除（β版）」は、FocusDPCが設定した2つのユーザー制限とアンインストール禁止を解除し、Private DNSを「自動」に戻します。以前のDNS設定を保存・復元する機能ではありません。Device Owner登録自体は残るため、アプリを削除する場合は後述のADB手順で解除してください。

## Android Studioでのビルド

必要な環境：

- Android Gradle Plugin 8.13.2に対応したAndroid Studio。
- Gradle JDK：JDK 17。
- Android SDK Platform 36、Android SDK Build Tools、Android SDK Platform-Tools。
- 初回のGradle・プラグイン取得用ネットワーク接続。

1. Android Studioの **Open** で、`settings.gradle` があるこの `android_limit_app` フォルダーを開きます。
2. SDK ManagerからSDK Platform 36とPlatform-Toolsをインストールします。
3. Settings → Build, Execution, Deployment → Build Tools → GradleでGradle JDKを17に設定します。
4. Gradle Syncを実行します。SDKの場所はAndroid Studioが `local.properties` に設定します。
5. BuildメニューからAPKをビルドするか、以下のコマンドを実行します。

macOS / Linux：

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
```

出力：

- アプリ：`app/build/outputs/apk/debug/app-debug.apk`
- テストAPK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Lint結果：`app/build/reports/lint-results-debug.html`

Gradle Wrapperを同梱し、配布ファイルのSHA-256検証を有効にしています。端末で使う追加ライブラリはKotlin標準ライブラリのみです。AndroidX・Compose・コルーチン・HTTPクライアントは追加していません。

ManifestにはDNS over TLSへの接続確認に必要な `INTERNET` 権限を宣言しています。Android 10の設定APIではアプリ側で接続確認を行うため必要です。通常権限なので、実行時の権限許可ダイアログは不要です。

## NextDNSの設定を変更

`app/src/main/java/io/github/focusdpc/PolicyConfig.kt` の次の1行を変更して、再ビルドしてください。

```kotlin
const val NEXTDNS_HOSTNAME = "YOUR_ID.dns.nextdns.io"
```

NextDNSの管理画面にあるAndroid / Private DNS向けのホスト名を、正確にコピーします。`https://`、ポート番号、パスは付けません。このIDはAPIキーではありませんが、公開リポジトリへ載せたくない場合は変更済みソースを公開しないでください。

設定APIはDNS over TLS（通常TCP 853）への接続を確認します。インターネットに接続できないAVD、853番ポートを遮断するネットワーク、到達不能なDNSホストでは失敗します。VPN併用時はVPNの内外からDNSへ到達できる必要があります。

## エミュレータへのインストール

1. Device ManagerでAndroid 10 / API 29以上の**テスト専用AVD**を作成します。アカウントや仕事用プロファイルは追加しません。
2. AVDを起動し、`adb devices` でシリアルを確認します。
3. 下記の `emulator-5554` は、実際のシリアルに置き換えます。複数端末がある場合も `-s` を省略しません。

```sh
adb devices
adb -s emulator-5554 install -t -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n io.github.focusdpc/.MainActivity
```

`android:testOnly="true"` をすべてのビルドで維持しているため、ADBでのインストールには **`-t` が必要**です。APKをタップする通常のインストールには対応しません。

アプリをインストール・起動するだけではDevice Ownerになりません。通常の「デバイス管理アプリを有効化」操作もDevice Owner登録とは異なります。

## ADBでDevice Ownerに登録

**まずエミュレータで実行してください。** Device Owner登録は、アカウント・他のOwner・追加ユーザー等がない、開発用に準備した端末が前提です。登録に失敗した場合は原因を確認し、既存の管理アプリやユーザーデータを強制削除しないでください。

```sh
adb -s emulator-5554 shell dpm set-device-owner --user 0 io.github.focusdpc/.MyDeviceAdminReceiver
```

`Success: Device owner set to ...` が表示されたら、FocusDPCを起動し「状態を更新」を押します。アプリ自身がDevice Ownerになる処理はありません。

確認コマンド（API 29以上で利用可能）：

```sh
adb -s emulator-5554 shell dumpsys device_policy
adb -s emulator-5554 shell dumpsys user
```

`dumpsys device_policy` にFocusDPCのDevice Ownerと管理コンポーネントが表示されることを確認します。新しいAndroidで対応している場合は `adb -s emulator-5554 shell dpm list-owners` でも確認できます。API 29では `list-owners` に依存しません。

## テスト方法

### 手動確認

1. Owner未登録で起動し、「ポリシーを適用」を押します。指定の未登録メッセージが表示され、クラッシュしないことを確認します。
2. Device Ownerに登録し、画面に「有効」が表示されることを確認します。
3. プレースホルダーのまま適用します。NextDNSは「未設定」、DNS変更禁止は「無効」、インストール禁止とDPCアンインストール禁止は「有効」になることを確認します。
4. 「制限を解除（β版）」を実行し、3つの制限が「無効」、Private DNSが「自動」に戻ることを確認します。
5. 自分のNextDNSホスト名で再ビルドし、通信できるAVDで適用します。全項目が有効／設定済みになり、Android設定画面からPrivate DNSを編集できなくなることを確認します。
6. 通常のAPKインストール・ストアからの新規インストール、およびAndroid設定からFocusDPCのアンインストールが制限されることを確認します。ADBは開発経路として残しているため、ADBでの操作だけを通常インストール禁止の判定に使わないでください。
7. アプリの再起動・画面回転・ボタン連打・設定画面からの復帰を試します。DNS接続確認中もUIが固まらず、処理が二重実行されないことを確認します。
8. 使い捨てAVDでネットワーク障害・DNS接続失敗も確認します。DNSエラーは画面に表示され、DNS変更禁止を新たに設定しないことを確認します。

### 自動確認（Python 3 + ADB、エミュレータ専用）

```sh
./gradlew assembleDebug assembleDebugAndroidTest
python3 scripts/emulator_smoke_test.py --serial emulator-5554
```

Windowsでは `python3` を `python` に置き換えます。テストは標準のInstrumentationを使用し、テスト用ライブラリも不要です。

このスクリプトは、実機とOwner登録済みAVDでは実行を拒否します。ホスト名の入力検証、未登録時の適用・解除、Owner登録後の適用、再適用、DNS接続失敗、制限解除、ADBでのOwner解除を検証します。終了時に、このスクリプトが登録したDevice Ownerを解除します。テストAPKは削除し、FocusDPC本体は未登録状態で残します。失敗時も回復処理を試みますが、エミュレータ切断時などは下記の手動解除手順を使用してください。

実際のNextDNSホストへの接続成功・フィルタリング確認は、自分のIDとDNS over TLSに接続可能なネットワークで行ってください。自動テストでダミーの成功結果を作って、実際のDNS接続が確認できたことにはしません。

## 制限・Device Ownerを解除して削除

1. アプリの「制限を解除（β版）」を実行します。DNSは「自動」に戻ります。
2. 次のADBコマンドでDevice Ownerを解除します。
3. Ownerがなくなったことを確認してからアンインストールします。

```sh
adb -s emulator-5554 shell dpm remove-active-admin --user 0 io.github.focusdpc/.MyDeviceAdminReceiver
adb -s emulator-5554 shell dumpsys device_policy
adb -s emulator-5554 uninstall io.github.focusdpc
```

UIが開けないときも、testOnlyの管理アプリに対する `remove-active-admin` が回復経路です。管理者の削除でその管理者の制限は除去されますが、**Private DNSの設定値は残る可能性があります**。Android設定画面から「自動」などに戻してください。

## よくあるビルド・インストールエラー

- `INSTALL_FAILED_TEST_ONLY`：`adb install` に `-t` を付けてください。
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：インストール済みAPKと新しいAPKの署名が異なります。付属APKから、自分のAndroid Studioで作ったAPKへ切り替える場合にも起こり得ます。上記の手順で制限とOwnerを解除し、旧APKをアンインストールしてから新しいAPKを入れてください。デバッグ用の秘密鍵は配布していません。
- macOS / Linuxで `./gradlew: Permission denied`：`chmod +x gradlew` を実行します。SDKの場所が見つからない場合はAndroid Studioでプロジェクトを開き、SDK Managerと `local.properties` の設定を確認してください。

## 開発中にDISALLOW_DEBUGGING_FEATURESを有効にしてはいけない理由

Device Ownerからこの制限を設定すると、USBデバッグを含むデバッグ機能が無効になります。ADBによるAPK更新・ログ取得・`remove-active-admin` が使えなくなり、バグで解除ボタンも使えない場合は初期化が必要になることがあります。このβ版では一切設定しません。`PolicyManager.kt` に将来の検討場所だけコメントで示しています。工場出荷状態へのリセットも禁止しません。

## arrows We2について

コードはAPI 29以上の標準Device Owner APIを使用します。arrows We2の実機での登録条件、メーカーの設定画面、DNS over TLS通信、解除手順は別途確認が必要です。Device Ownerは既に普段使いしている端末へ通常アプリのように追加できる機能ではありません。まずテスト用AVDで登録から解除まで確認し、実機での登録はデータのバックアップと開発用端末の準備をしたうえで行ってください。

## 構成

```text
android_limit_app/
├── READ_ME.txt                         元の仕様（そのまま保存）
├── README.md                           この説明書
├── docs/SETUP_GUIDE.md                  実機セットアップとトラブル対応
├── docs/DETAIL_DESIGN.md                クラス・メソッドごとの設計
├── settings.gradle / build.gradle      Gradle構成
├── gradlew / gradlew.bat / gradle/      Gradle Wrapper
├── scripts/emulator_smoke_test.py       登録・解除まで行う自動テスト
└── app/src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── java/io/github/focusdpc/
    │   │   ├── MainActivity.kt           状態と操作結果の表示
    │   │   ├── MyDeviceAdminReceiver.kt  端末管理者の登録先
    │   │   ├── PolicyConfig.kt           NextDNS定数と入力検証
    │   │   ├── PolicyManager.kt          Android管理APIの呼び出し
    │   │   ├── PolicyState.kt            状態・操作結果のデータ
    │   │   ├── PolicyTaskRunner.kt       非同期処理と二重実行防止
    │   │   └── FocusDpcApplication.kt    画面回転をまたぐ処理の保持
    │   └── res/                          画面、文言、テーマ、管理XML
    └── androidTest/java/io/github/focusdpc/PolicyInstrumentation.kt
```

## 確認した公式仕様

2026-10-12に以下のAndroid Developersの仕様を確認し、SDK 36でビルドします。非推奨の `AsyncTask`・`clearDeviceOwnerApp()`・汎用 `setGlobalSetting()` は使用していません。

- [DevicePolicyManager / Private DNS設定](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setGlobalPrivateDnsModeSpecifiedHost(android.content.ComponentName,%20java.lang.String))：Device Owner専用のブロッキング処理、成功・失敗コードを確認。
- [UserManager / Private DNS変更禁止](https://developer.android.com/reference/android/os/UserManager#DISALLOW_CONFIG_PRIVATE_DNS)。
- [UserManager / インストール禁止](https://developer.android.com/reference/android/os/UserManager#DISALLOW_INSTALL_APPS)。
- [UserManager / デバッグ機能の制限](https://developer.android.com/reference/android/os/UserManager#DISALLOW_DEBUGGING_FEATURES)。
- [ADB / Device Policy Managerコマンド](https://developer.android.com/tools/adb#dpm)：`testOnly` と `remove-active-admin` の条件を確認。
- [AOSP / Android 10のDevicePolicyManager実装](https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/core/java/android/app/admin/DevicePolicyManager.java)：DNS接続確認がアプリ側で行われることを確認。Manifestの通信権限とワーカースレッドを使用します。

今回の環境での具体的な検証結果は、付属の `VERIFICATION.md` を参照してください。
