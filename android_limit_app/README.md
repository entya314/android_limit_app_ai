# FocusDPC β版

自分のAndroidスマートフォンの利用環境を制限する、端末単体で動くDevice Policy Controller（DPC）です。元の要件は `READ_ME.txt` に残しています。企業向けMDMサーバー、アカウント連携、バックグラウンド監視サービスは使用しません。

Kotlin / Android 10（API 29）以上。対象端末は **arrows We2** ですが、まず使い捨てのAndroid Emulatorで動作確認してください。

## 作業手順書・詳細設計書

- [作業手順書](docs/SETUP_GUIDE.md)：Windows／Android Studioでのビルドから、実機の登録・適用・確認・解除まで。今回つまずいた点と対処も記載しています。
- [詳細設計書](docs/DETAIL_DESIGN.md)：クラスの役割、private以外のメソッドの入力・出力・処理、データ構造と非同期処理の説明です。
- [0.1.2-betaへの更新とADBでの追加](docs/UPGRADE_012.md)：旧版の一括インストール禁止を一時解除し、Owner登録を保って更新する手順です。

## 最初に読むところ

1. `app/src/main/java/io/github/focusdpc/PolicyConfig.kt`：NextDNSのホスト名を変更する場所。
2. `PolicyManager.kt`：Device Owner確認、状態取得、適用、テスト後の後片付け。日本語コメントに処理の理由を書いています。
3. `MainActivity.kt`：状態表示とボタン操作。
4. `PolicyTaskRunner.kt`：DNS処理を別スレッドで実行し、画面回転時の二重実行を防ぐ部分。

コメントはコードの説明です。不要なコードを大量にコメントアウトして残すことはせず、β版で実行しない制限はコメントで明示しています。

## このβ版の動作

「ポリシーを適用」を押すと、Device Ownerであることを確認して以下を実行します。

| 項目 | 使用する標準API |
| --- | --- |
| NextDNSのPrivate DNS設定 | `setGlobalPrivateDnsModeSpecifiedHost()` |
| Private DNSの設定変更禁止 | `addUserRestriction(DISALLOW_CONFIG_PRIVATE_DNS)` |
| Playストアの利用禁止 | `setApplicationHidden(..., "com.android.vending", true)` |
| スマホ上で開く通常APKのインストール禁止 | `addUserRestriction(DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)` |
| ADBインストールの許可 | 旧版の `DISALLOW_INSTALL_APPS` を `clearUserRestriction()` で解除 |
| FocusDPC自身のアンインストール禁止 | `setUninstallBlocked()` |

**0.1.2-betaは、ADBからのアプリ追加を許可し、Playストアからの追加を禁止する版です。** Playストアは非表示・利用不可となり、閲覧や既存アプリの自動更新も止まります。通常APKの禁止はADBには適用されません。他メーカーのストアやAndroidが信頼する別のインストーラーまで一律に禁止する仕様ではありません。

ビルド・Lintは成功していますが、0.1.2の実行テストはクラウドAVDのOS不調で未完走です。[検証結果](VERIFICATION.md#012-betaadb許可playストア禁止)を確認し、まずテスト用スマホで確認してください。

Playストアの非表示と通常APK禁止の成功を確認してから、旧版の一括禁止を外します。どちらかが失敗した場合は一括禁止を外さず、部分失敗を表示します。旧版の制限中は新版APK自体の導入も止まるため、初回だけ[移行手順](docs/UPGRADE_012.md)が必要です。

**初期値は指定どおり `YOUR_ID.dns.nextdns.io` です。** 自分のホスト名へ変更するまではDNS設定を行いません。DNS設定が失敗した場合も、ストア・通常APKの制限とFocusDPCのアンインストール禁止は個別に適用し、画面に各項目の結果を表示します。DNS設定が成功し、実際のホスト名も確認できた場合にだけ、Private DNS変更禁止を新たに有効にします。

一括トランザクションではないため、部分的な適用があり得ます。失敗時に既存の制限を勝手に解除することはありません。現在の状態は毎回AndroidのAPIから読み取ります。「設定済み」は対象ホストがPrivate DNSに指定されていることを示し、現在の通信・フィルタリングが成功している保証ではありません。

Device Ownerでないときの適用は、**「このアプリはDevice Ownerではありません」** と表示します。権限が必要なDNS・アンインストール禁止の状態は「未確認」と表示し、無効だと誤表示しません。

このβ版は以下の制限を追加しません。

- ADB・USBデバッグ・開発者向けオプションの禁止。
- 工場出荷状態へのリセット禁止、Factory Reset Protectionの設定。
- Device Ownerの解除を妨げる処理、自動再登録、端末データ消去。

**0.1.1-betaから、画面の「制限を解除」ボタンと確認ダイアログを削除しました。** 画面の操作は「ポリシーを適用」と「状態を更新」の2つです。アプリ内から制限を解除する操作はありません。

実機で全解除する場合は、[PCのデバッガーからの解除](docs/UPGRADE_012.md#4-pcから全ポリシーを解除する場合)でPlayストアを再表示し、DNSを「自動」へ戻します。画面やPolicyTaskRunnerからは解除を呼び出せません。ADBと出荷時リセットによる復旧は可能です。

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
3. プレースホルダーのまま適用します。NextDNSは「未設定」、DNS変更禁止は「無効」、Playストア禁止・通常APK禁止・ADB許可・DPCアンインストール禁止は「有効」になることを確認します。Playストア未導入の端末では、もともと利用不可のためストア禁止は有効として扱います。
4. 画面に解除ボタンがないことを確認します。PCからの全解除は[デバッガーでの解除手順](docs/UPGRADE_012.md#4-pcから全ポリシーを解除する場合)で行い、Playストアが再び表示されることを確認します。Owner登録はこの操作では残ります。
5. 自分のNextDNSホスト名で再ビルドし、通信できるAVDでDevice Ownerへ再登録してから適用します。全項目が有効／設定済みになり、Android設定画面からPrivate DNSを編集できなくなることを確認します。
6. Playストアを起動できず、スマホ上で開く通常APKのインストールとFocusDPCの削除も制限されることを確認します。一方、ADBでは新規APKの導入と同じ署名のAPK更新が成功することを確認します。
7. アプリの再起動・画面回転・ボタン連打・設定画面からの復帰を試します。DNS接続確認中もUIが固まらず、処理が二重実行されないことを確認します。
8. 使い捨てAVDでネットワーク障害・DNS接続失敗も確認します。DNSエラーは画面に表示され、DNS変更禁止を新たに設定しないことを確認します。

### 自動確認（Python 3 + ADB、エミュレータ専用）

```sh
./gradlew assembleDebug assembleDebugAndroidTest
python3 scripts/emulator_smoke_test.py --serial emulator-5554
```

Windowsでは `python3` を `python` に置き換えます。テストは標準のInstrumentationを使用し、テスト用ライブラリも不要です。

このスクリプトは、実機とOwner登録済みAVDでは実行を拒否します。従来の検証に加え、旧版制限からの移行、制限中のADB新規導入・更新・DPC自身の更新、Playストア相当パッケージの非表示を確認します。PlayストアがないAVDには空の代役APKを一時導入し、非表示で起動できなくなることと、未導入時の動作を検証します。本物のGoogle Playでの通信・更新を再現するテストではありません。

代役APKはSDK Build Tools 36.0.0とビルド時のdebug.keystoreで作成します。終了時は、このスクリプトが登録したOwnerだけを解除し、テストAPKと自分で入れた代役APKを削除します。既存のPlayストアを代役で置換しません。FocusDPC本体は未登録状態で残します。失敗時も回復処理を試みますが、エミュレータ切断時などは下記の手動解除手順を使用してください。

実際のNextDNSホストへの接続成功・フィルタリング確認は、自分のIDとDNS over TLSに接続可能なネットワークで行ってください。自動テストでダミーの成功結果を作って、実際のDNS接続が確認できたことにはしません。

## 制限・Device Ownerを解除して削除

1. 先に[PCからの全ポリシー解除](docs/UPGRADE_012.md#4-pcから全ポリシーを解除する場合)でPlayストアを再表示します。Ownerだけを先に外すと、OSによっては非表示状態が残る場合があります。
2. 次のADBコマンドでDevice Ownerを解除します。
3. Ownerがなくなったことを確認します。Private DNSの設定値が残る場合はAndroid設定画面から「自動」などへ戻します。
4. アプリを使わない場合はアンインストールします。

```sh
adb -s emulator-5554 shell dpm remove-active-admin --user 0 io.github.focusdpc/.MyDeviceAdminReceiver
adb -s emulator-5554 shell dumpsys device_policy
```

削除する場合：

```sh
adb -s emulator-5554 uninstall io.github.focusdpc
```

画面に解除ボタンはありません。UIが開けないときも、testOnlyの管理アプリに対する `remove-active-admin` が回復経路です。Owner解除後は、Googleアカウントなどの状態によって再登録できない場合があるため、必要なAPK更新は同じ署名での上書きを使用してください。

## よくあるビルド・インストールエラー

- `INSTALL_FAILED_TEST_ONLY`：`adb install` に `-t` を付けてください。
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：インストール済みAPKと新しいAPKの署名が異なります。付属APKから、自分のAndroid Studioで作ったAPKへ切り替える場合にも起こり得ます。上記の手順で制限とOwnerを解除し、旧APKをアンインストールしてから新しいAPKを入れてください。デバッグ用の秘密鍵は配布していません。
- macOS / Linuxで `./gradlew: Permission denied`：`chmod +x gradlew` を実行します。SDKの場所が見つからない場合はAndroid Studioでプロジェクトを開き、SDK Managerと `local.properties` の設定を確認してください。

## 開発中にDISALLOW_DEBUGGING_FEATURESを有効にしてはいけない理由

Device Ownerからこの制限を設定すると、USBデバッグを含むデバッグ機能が無効になります。ADBによるAPK更新・ログ取得・`remove-active-admin` が使えなくなり、現在は画面にも解除操作がないため、復旧に初期化が必要になることがあります。このβ版では一切設定しません。`PolicyManager.kt` に将来の検討場所だけコメントで示しています。工場出荷状態へのリセットも禁止しません。

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
- [UserManager / 提供元不明アプリ禁止](https://developer.android.com/reference/android/os/UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)：ADBはこの禁止の対象外です。
- [DevicePolicyManager / アプリ非表示](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setApplicationHidden(android.content.ComponentName,%20java.lang.String,%20boolean))：非表示のパッケージは利用不可となり、APKとデータは残ります。
- [UserManager / デバッグ機能の制限](https://developer.android.com/reference/android/os/UserManager#DISALLOW_DEBUGGING_FEATURES)。
- [ADB / Device Policy Managerコマンド](https://developer.android.com/tools/adb#dpm)：`testOnly` と `remove-active-admin` の条件を確認。
- [AOSP / Android 10のDevicePolicyManager実装](https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/core/java/android/app/admin/DevicePolicyManager.java)：DNS接続確認がアプリ側で行われることを確認。Manifestの通信権限とワーカースレッドを使用します。

今回の環境での具体的な検証結果は、付属の `VERIFICATION.md` を参照してください。
