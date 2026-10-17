# FocusDPC β版 作業手順書

- 作成日：2026-10-17
- 対象：FocusDPC 0.1.1-beta／Windows／Android Studio／Android 10以上の実機
- 対応する実装：本リポジトリの0.1.1-beta（画面の解除ボタン削除版）

今回のセットアップで実際につまずいた点を反映した手順です。本番スマホでも、現在のβ版を使って実機テストするための説明です。

0.1.1-betaでは、画面の解除ボタンを削除しました。実機での解除は、後述するPCからのADB手順を使います。

- [1. 全体の流れと用語](#1-全体の流れと用語)
- [2. PCとプロジェクトを準備する](#2-pcとプロジェクトを準備する)
- [3. NextDNSを設定してAPKを作る](#3-nextdnsを設定してapkを作る)
- [4. スマホを準備してUSB接続する](#4-スマホを準備してusb接続する)
- [5. インストールして起動する](#5-インストールして起動する)
- [6. Device Ownerの登録と解除を確認する](#6-device-ownerの登録と解除を確認する)
- [7. 必要なアプリを準備してポリシーを適用する](#7-必要なアプリを準備してポリシーを適用する)
- [8. 実機テストの完了を確認する](#8-実機テストの完了を確認する)
- [9. 制限の解除とアプリの削除](#9-制限の解除とアプリの削除)
- [10. つまずいた点と対処一覧](#10-つまずいた点と対処一覧)
- [11. 今回の確認記録と次回の記録欄](#11-今回の確認記録と次回の記録欄)

関連資料：[詳細設計書](DETAIL_DESIGN.md)／[README](../README.md)／[開発環境での検証結果](../VERIFICATION.md)

## 1. 全体の流れと用語

次の順番で進めます。

1. Android Studioでプロジェクトを開く。
2. NextDNSのホスト名をソースに設定してビルドする。
3. スマホを準備して、USBデバッグ接続を確認する。
4. APKをインストールする。
5. Device Ownerの登録 → 解除テスト → 再登録を行う。
6. 必要なアプリを先に用意する。
7. アプリの「ポリシーを適用」を押す。
8. 設定・制限・通信・再起動後の状態を確認する。

| 用語 | この手順での意味 |
| --- | --- |
| APK | スマホにインストールするアプリのファイル。今回は `app-debug.apk` |
| ADB | PCからAndroid端末へのインストールや管理コマンドを実行するツール |
| Device Owner | 端末全体の設定を管理できる特別な立場。インストールとは別に登録する |
| ポリシー | NextDNS設定、DNS変更禁止、アプリインストール禁止、自身のアンインストール禁止 |
| NextDNS設定ID | `abcdef.dns.nextdns.io` の `abcdef` の部分 |
| NextDNSホスト名 | 上記の `abcdef.dns.nextdns.io` 全体。アプリに設定するのはこちら |

このβ版では、復旧用のADB・USBデバッグと出荷時リセットを禁止しません。Manifestの `android:testOnly="true"` を維持し、テスト中は許可済みPCからADB接続できる状態を保ちます。

## 2. PCとプロジェクトを準備する

### 2.1 プロジェクトの正しいフォルダーを開く

リポジトリを取得し、Android Studioの「Open」から、**`settings.gradle` と `gradlew.bat` が入っている `android_limit_app` フォルダー**を開きます。

~~~text
android_limit_app_ai/
└── android_limit_app/          ← Android Studioで開く場所
    ├── settings.gradle
    ├── gradlew.bat
    ├── app/
    └── docs/
~~~

同じリポジトリのコピーが複数ある場合は、編集・ビルド・インストールを同じコピーで行ってください。今回も、ビルド時とインストール時で別の保存場所を使っていました。

必要な環境は次のとおりです。

| 項目 | 構成 |
| --- | --- |
| Java | 検証済みの環境はJDK 17 |
| Android SDK Platform | 36 |
| Android SDK Build-Tools | 36.0.0 |
| Android SDK Platform-Tools | `adb` を使用するために必要 |
| Gradle | 同梱Wrapperが8.13を取得。別途インストール不要 |

SDKはAndroid StudioのSDK Managerで用意します。Gradle JDKは「Settings → Build, Execution, Deployment → Build Tools → Gradle」で確認できます。

### 2.2 Android Studioのターミナルを開く

上部メニューの「View → Tool Windows → Terminal」を開きます。Windowsでは `Alt + F12` でも開けます。

以降のコマンドは **Windows PowerShell** 用です。先頭が `PS D:\...>` のように表示される画面で実行します。コマンドの前に表示される `PS D:\...>` 自体は入力しません。

現在の場所を確認します。

~~~powershell
Get-Location
Test-Path .\gradlew.bat
~~~

`Test-Path` が `True` なら、ビルド用のフォルダーにいます。`False` なら、Android Studioで開いたフォルダーやターミナルの場所を見直してください。

### 2.3 Javaが認識されない場合

今回のエラー：

~~~text
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
~~~

Android Studio付属のJavaを使う場合は、次を1行ずつ実行します。

~~~powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Test-Path "$env:JAVA_HOME\bin\java.exe"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
~~~

`Test-Path` が `False` なら、Android Studioのインストール先が異なります。実際のJavaのフォルダーを `JAVA_HOME` に指定してください。末尾に `\bin` は付けません。

付属JavaのバージョンはAndroid Studioによって異なります。Javaの互換性エラーが出る場合は、検証済みのJDK 17を指定してください。**Android StudioのGradle JDK設定と、ターミナルのJava設定は別なので、片方だけ変更しても解消しない場合があります。**

`$env:...` の設定は、今開いているPowerShell内だけで有効です。ターミナルを閉じた場合は再設定してください。

### 2.4 ADBが認識されない場合

まず確認します。

~~~powershell
adb version
~~~

「認識されません」「見つかりません」と表示された場合は、SDK Managerで確認した「Android SDK Location」の下にある `platform-tools` をPATHへ追加します。標準の保存先なら次のコマンドです。

~~~powershell
$sdkPath = "$env:LOCALAPPDATA\Android\Sdk"
Test-Path "$sdkPath\platform-tools\adb.exe"
$env:Path = "$sdkPath\platform-tools;$env:Path"
adb version
~~~

`False` の場合は、Platform-Toolsをインストールするか、`$sdkPath` を実際のSDKの場所へ変更してください。この設定も現在のPowerShell内で有効です。

## 3. NextDNSを設定してAPKを作る

### 3.1 設定IDではなく、ホスト名全体を指定する

NextDNS管理画面の「セットアップ」にある **DNS-over-TLS** のホスト名をコピーします。

例として表示が `abcdef.dns.nextdns.io` なら、IDは `abcdef`、アプリへ書く値は `abcdef.dns.nextdns.io` 全体です。例のIDは自分の値に置き換えます。

[PolicyConfig.kt](../app/src/main/java/io/github/focusdpc/PolicyConfig.kt) を開き、次の定数を変更して保存します。

~~~kotlin
const val NEXTDNS_HOSTNAME = "abcdef.dns.nextdns.io"
~~~

- 初期値の `YOUR_ID.dns.nextdns.io` のままでは適用できません。
- `https://`、パス、ポート番号、前後の空白は付けません。
- 設定はAPKに埋め込まれます。ソースを変更した後は、再ビルドと上書きインストールが必要です。
- NextDNSの実際の設定IDを公開したくない場合は、変更済み定数をそのまま公開リポジトリへpushしないでください。

### 3.2 ビルドする

~~~powershell
.\gradlew.bat assembleDebug
~~~

最後に `BUILD SUCCESSFUL` と表示されれば成功です。今回表示された `A restricted method in java.lang.System has been called` などの警告があっても、最後の結果は成功でした。`BUILD FAILED` の場合は先にエラーを解消します。

APKの存在を確認します。

~~~powershell
Test-Path .\app\build\outputs\apk\debug\app-debug.apk
~~~

`True` になってから先へ進みます。`False` の場合は、ビルドの成否と現在のフォルダーを確認してください。

## 4. スマホを準備してUSB接続する

### 4.1 アカウントと初期化について

Device Owner登録には端末側の条件があります。今回も「端末にアカウントが残っている」というエラーが出ました。既に初期化済みでアカウント未登録のテスト端末なら、再度初期化する必要はありません。

テスト専用の状態から始める場合は、次の順番で準備します。

1. 必要なデータをバックアップする。
2. スマホの「設定」からGoogleなどのアカウントを端末から削除する。Googleアカウント自体の削除とは異なります。
3. スマホの「設定」から出荷時リセットを行う。
4. 初期設定ではWi-Fiに接続し、Googleアカウントへのログインとバックアップの復元をスキップする。
5. 仕事用プロファイルや追加ユーザーを作らずに登録テストへ進む。

**出荷時リセットでは端末内のデータが消えます。** 本番スマホなら、LINE、認証アプリ、電子マネー、eSIMなどの引き継ぎ・再発行方法も確認してください。アカウントを端末から外す前に、未同期データも保存します。

初期化せずに進めたい場合は、まず端末からすべてのアカウントを削除して登録を試す方法もあります。それでも登録できない場合は、エラーを確認してから初期化を検討してください。

### 4.2 USBデバッグを有効にする

1. 「設定 → デバイス情報」などから「ビルド番号」を7回タップし、開発者向けオプションを表示する。
2. 開発者向けオプションで「USBデバッグ」を有効にする。
3. データ通信に対応したUSBケーブルでPCへ接続する。
4. スマホ側の「USBデバッグを許可」を承認する。

設定名や場所は端末によって異なります。テスト中は、USBデバッグを有効のままにします。

### 4.3 接続を確認する

**この手順書では、本番／テスト対象スマホ1台だけを接続し、エミュレータも終了した状態で進めます。**

~~~powershell
adb devices -l
~~~

表示例：

~~~text
List of devices attached
ABC123456    device product:... model:...
~~~

| 表示 | 対応 |
| --- | --- |
| `device` | 接続済み。先へ進める |
| `unauthorized` | スマホ画面でUSBデバッグを許可する |
| `offline` | USBの抜き差し、接続許可、端末の状態を確認する |
| 一覧が空 | ケーブル、USB端子、USBデバッグ、Windows用メーカーUSBドライバーを確認する |
| 複数行ある | 対象以外の端末・エミュレータを外すか終了する |

以前の説明に出てきた `SERIAL` は、実際の識別番号に置き換えるための仮の文字でした。そのまま実行すると `device 'SERIAL' not found` になります。また、識別番号を画像から読み取ると英字と数字を間違えることがあります。

**1台だけなら、この後のように `-s SERIAL` を省略できます。** 複数台を接続して操作する場合は、`adb devices -l` の先頭の識別番号をコピーし、`adb -s 実際の識別番号 ...` と指定してください。

## 5. インストールして起動する

ビルドが成功したのと同じAndroid Studioのターミナルで実行します。

~~~powershell
adb install -t -r app\build\outputs\apk\debug\app-debug.apk
~~~

`Success` と表示されたら、アプリを開きます。

~~~powershell
adb shell am start -n io.github.focusdpc/.MainActivity
~~~

`-t` はtestOnlyアプリの許可、`-r` は同じ署名の既存アプリを更新する指定です。APKをスマホ上でタップする通常のインストールではなく、ADBを使います。

初回でまだDevice Ownerに登録していなければ、アプリの「ポリシーを適用」で「このアプリはDevice Ownerではありません」と出ます。これは登録前の正常な結果です。Android設定画面で「デバイス管理アプリ」を有効にするだけではDevice Owner登録にはなりません。

## 6. Device Ownerの登録と解除を確認する

### 6.1 登録する

~~~powershell
adb shell dpm set-device-owner --user 0 io.github.focusdpc/.MyDeviceAdminReceiver
~~~

`Success: Device owner set to ...` と表示されれば成功です。アプリの「状態を更新」を押し、Device Ownerが「有効」になることを確認します。

**この段階では、まだ「ポリシーを適用」を押しません。**

登録エラーの主な分岐：

- `there are already some accounts on the device`：Googleなどのアカウントが端末に残っています。「4.1」に戻って確認します。
- `device owner (...) is already set`：括弧内が `io.github.focusdpc/.MyDeviceAdminReceiver` なら、今回のFocusDPCが既に登録済みです。登録コマンドを繰り返さず、次の解除テストへ進みます。
- 別の管理アプリがOwnerと表示される場合：その端末の管理状況を確認します。この手順で他の管理アプリを強制解除しないでください。

必要なら、登録状態を次のコマンドでも確認できます。

~~~powershell
adb shell dumpsys device_policy
~~~

### 6.2 先に解除できることを確認する

~~~powershell
adb shell dpm remove-active-admin --user 0 io.github.focusdpc/.MyDeviceAdminReceiver
~~~

`Success` と表示され、アプリの「状態を更新」でDevice Ownerが「無効」になれば解除テスト完了です。

エラーが出た場合は、ポリシー適用へ進まずに原因を確認します。今回のβ版では、`android:testOnly="true"` の設定がこの解除経路の前提です。

### 6.3 再登録する

~~~powershell
adb shell dpm set-device-owner --user 0 io.github.focusdpc/.MyDeviceAdminReceiver
~~~

登録成功と、アプリのDevice Owner「有効」を確認します。

## 7. 必要なアプリを準備してポリシーを適用する

### 7.1 適用前の準備

**適用後は新しいアプリのインストールが禁止されます。** 本番スマホで必要なアプリのインストール、ログイン、データ移行は、この段階で済ませてください。

通常のインストール禁止を試す場合は、確認用の一般アプリ／APKを用意し、制限前ならインストールできることを確認して、一度削除しておくと比較できます。

### 7.2 適用する

スマホのFocusDPCを開き、「状態を更新」→「ポリシーを適用」の順で操作します。NextDNSへの接続確認には数秒以上かかる場合があります。

期待する表示：

| 画面の項目 | 期待する値 |
| --- | --- |
| Device Owner | 有効 |
| NextDNS | 設定済み |
| Private DNS変更禁止 | 有効 |
| アプリインストール禁止 | 有効 |
| DPCアンインストール禁止 | 有効 |

画面の「設定先」と「現在のDNSホスト」が、自分のNextDNSホスト名になっていることも確認します。

**DNSに失敗しても、インストール禁止と自身のアンインストール禁止は個別に適用されます。** エラーが出た場合は「何も設定されていない」と判断せず、各項目と操作結果を確認してください。DNS成功前にDNS変更禁止を新しく追加することはありませんが、以前の適用で付いた制限を失敗時に自動解除することもありません。

### 7.3 NextDNSのIDエラーが出た場合

`NextDNSのIDが未設定、またはホスト名が不正です。` と出たら、次を確認します。

1. `PolicyConfig.kt` の値が `YOUR_ID.dns.nextdns.io` のままではないか。
2. NextDNSのDNS-over-TLSホスト名全体を指定しているか。
3. 変更を保存して、同じプロジェクトで再ビルドしたか。
4. 新しいAPKを `adb install -t -r ...` で上書きしたか。

`DNS over TLSへの接続を確認できませんでした。` は、形式エラーとは別です。ホスト名のコピー間違い、インターネット接続、TCP 853への到達性を確認し、必要ならWi-Fiとモバイル回線を切り替えて試してください。

## 8. 実機テストの完了を確認する

画面上の成功表示に加えて、実際の動作を確認します。

- [ ] アプリの5項目が上記の期待値になる。
- [ ] 操作ボタンは「ポリシーを適用」「状態を更新」の2つで、解除ボタンが表示されない。
- [ ] Androidの設定画面からプライベートDNSを変更できない。
- [ ] 通常のストア／APK操作で、新しいアプリのインストールが制限される。
- [ ] Androidの設定画面からFocusDPCをアンインストールできない。
- [ ] ブラウザーや必要なアプリで通信できる。
- [ ] ログを有効にしたNextDNS管理画面で、この端末からの確認用通信が記録される。
- [ ] 必要に応じ、NextDNSで指定したテスト用ドメインのブロック動作を確認する。
- [ ] スマホを再起動して、設定と制限が維持される。
- [ ] 再起動後も、許可済みPCから `adb devices -l` で接続できる。

アプリの「NextDNS：設定済み」は、AndroidのDNSモードとホスト名を確認した表示です。現在の全通信やフィルタリングの成功を保証する表示ではないため、通信・ログも別に確認します。

ADBは開発・復旧経路として残しています。**ADB経由のインストール結果だけで、通常操作のインストール禁止が効いているかを判定しないでください。**

実機ではこの手動手順を使います。付属の `scripts/emulator_smoke_test.py` はエミュレータ専用であり、本番スマホには実行しません。

## 9. 制限の解除とアプリの削除

### 9.1 ADBでDevice Ownerと制限を解除する

**アプリ内に解除ボタンはありません。** USBデバッグを許可したPCへ接続し、対象スマホだけが接続されていることを確認して実行します。

~~~powershell
adb shell dpm remove-active-admin --user 0 io.github.focusdpc/.MyDeviceAdminReceiver
adb shell dumpsys device_policy
~~~

`Success` と表示され、Ownerが解除されたことを確認します。この管理者が設定した制限は除去されます。他の管理者による制限まで解除するものではありません。

Private DNSのホスト名設定は残る可能性があります。Androidの「設定」でプライベートDNSを開き、「自動」などへ戻してください。変更前のDNS設定を復元する処理ではありません。

Owner解除後にGoogleアカウントなどが残っていると、再登録の条件に引っかかる場合があります。Owner登録を保ったまま画面から制限だけを解除する機能はありません。

### 9.2 アプリを削除する

前項でOwnerが解除されたことを確認してから実行します。

~~~powershell
adb uninstall io.github.focusdpc
~~~

アプリが開けない場合も、`remove-active-admin` が復旧経路です。

### 9.3 再ビルドしたAPKを更新する場合

同じPC・同じデバッグ署名なら、再ビルド後に次で上書きします。NextDNS定数が自分のホスト名のままであることを確認してください。

解除ボタンのある0.1.0-betaから0.1.1-betaへの更新も、同じ署名で上書きします。ボタン削除のために、初期化やOwnerの再登録をする必要はありません。既存のポリシーは維持されるため、更新後に「状態を更新」で確認します。

~~~powershell
adb install -t -r app\build\outputs\apk\debug\app-debug.apk
~~~

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` は、旧APKと新APKの署名が異なるエラーです。ADBでOwner解除 → 必要に応じてDNSを手動で自動へ変更 → 旧アプリ削除 → 新APKインストール → Owner再登録の順にやり直します。アカウントを追加した後は再登録条件に引っかかる場合があるため、その時点のエラーを確認してください。

## 10. つまずいた点と対処一覧

### 10.1 今回のセットアップで出たもの

| 症状・表示 | 原因／判断 | 対処 |
| --- | --- | --- |
| Android Studioのターミナルが見つからない | ツールウィンドウの場所が分からなかった | View → Tool Windows → Terminal、またはAlt + F12 |
| `JAVA_HOME is not set` | ターミナルにJavaの場所が設定されていなかった | JAVA_HOMEとPATHを設定し、`java -version` を確認 |
| `device 'SERIAL' not found` | 仮の文字列をそのまま入力していた | 1台だけ接続して `-s SERIAL` を省略 |
| 実際の番号を指定しても `device '...' not found` | 画像から読み取った端末番号に読み違いがあった | 1台接続なら番号を省略。複数台なら端末一覧からコピー |
| `failed to stat ... No such file or directory` | 指定先にAPKがなかった。ビルドとインストールで異なるフォルダーを使っていた | ビルドしたターミナルでAPKの存在を確認してからインストール |
| JavaのWARNINGが出た | 今回の画面では最終結果は成功だった | 最後の `BUILD SUCCESSFUL` とAPKの存在を確認 |
| `there are already some accounts on the device` | Device Owner登録の条件にアカウントが抵触 | アカウントを端末から削除。必要に応じバックアップ後に初期化 |
| `device owner (...) is already set` | 今回のFocusDPCが既にOwner登録済みだった | 解除テストへ進み、解除成功後に再登録 |
| NextDNSのID未設定・ホスト名不正エラー | 設定IDとホスト名、設定場所の確認が必要だった | 自分のホスト名全体を定数へ設定し、再ビルド・上書き |

### 10.2 今後出る可能性があるもの

| 症状・表示 | 対処 |
| --- | --- |
| `adb` が認識されない | SDKのPlatform-Toolsをインストールし、ターミナルのPATHへ追加 |
| `unauthorized` | スマホ側でUSBデバッグを許可 |
| `more than one device/emulator` | 対象以外を外す・終了するか、実際の識別番号を `-s` で指定 |
| `INSTALL_FAILED_TEST_ONLY` | インストールコマンドに `-t` を付ける |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 署名の不一致。「9.3」の順で旧版を解除・削除して入れ直す |
| 「このアプリはDevice Ownerではありません」 | インストールとは別にOwner登録が必要。登録済みなら状態を更新 |
| DNS over TLSの接続エラー | 実際のホスト名、インターネット接続、TCP 853、VPNなどを確認 |
| Owner登録・解除の想定外のエラー | 後続の適用を止め、コマンドとエラー全文を記録する |

## 11. 今回の確認記録と次回の記録欄

### 11.1 会話で確認できたこと

2026-10-17時点、0.1.0-betaでの記録です。0.1.1-betaの解除ボタン削除後については、本番スマホで改めて確認してください。

- ADBの接続一覧にはモデル `SO-52E` と表示されていました。
- Windows／Android Studioのターミナルで `BUILD SUCCESSFUL` を確認しました。
- アカウントによる登録エラーと、FocusDPCがOwner登録済みの表示を確認しました。
- NextDNSの設定案内後、「出来ました」、確認項目の案内後に「OK」「現状完璧」と報告をいただきました。
- 次は本番スマホでのテストを進める予定です。

OSバージョン、初期化を実施したかどうか、解除テストや各チェックの個別ログは採取していません。上記は利用者からの報告を含む記録であり、開発環境の自動テスト結果とは分けて扱います。SO-52Eでの報告を、要件上の対象機種であるarrows We2の確認結果として扱うこともしません。

### 11.2 本番スマホで残す記録

| 項目 | 記録 |
| --- | --- |
| 実施日 | |
| 端末メーカー／モデル | |
| Androidバージョン | |
| 使用したソースのコミット | |
| APKのビルド日時／ビルドしたPC | |
| 初期化の実施有無 | |
| Owner登録 → 解除 → 再登録の結果 | |
| 5項目の表示結果 | |
| 通信／NextDNSログ／ブロック確認 | |
| 再起動後の状態／ADB接続 | |
| 制限解除・Owner解除の結果 | |
| エラー全文と対処 | |

実際の設定ID、アカウント情報、端末識別番号などを含む記録を公開する場合は、掲載する範囲を確認してください。

参考：[ADBのDevice Policy Managerコマンド](https://developer.android.com/tools/adb#dpm)／[Androidのデバイス保護とアカウント削除](https://support.google.com/android/answer/9459346?hl=ja)
