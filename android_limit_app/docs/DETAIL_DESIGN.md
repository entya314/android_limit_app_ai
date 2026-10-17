# FocusDPC β版 詳細設計書

- 作成日：2026-10-17
- 対象：FocusDPC 0.1.0-beta
- 対応する実装：[6916e86](https://github.com/entya314/android_limit_app_ai/commit/6916e863b0bcc8c01632d4f80d4d9236a52d758d)

実装済みコードを基に、クラスの役割と、private以外のメソッドの入力・出力・主な処理をまとめています。privateメソッドと、標準ライブラリ／Androidから継承しただけのメソッドの個別説明は省略します。Kotlinの `Unit` は「戻り値としてデータを返さない」という意味です。

- [1. システム概要](#1-システム概要)
- [2. クラス構成と処理の流れ](#2-クラス構成と処理の流れ)
- [3. MainActivity](#3-mainactivity)
- [4. FocusDpcApplication](#4-focusdpcapplication)
- [5. PolicyTaskRunner](#5-policytaskrunner)
- [6. PolicyManager](#6-policymanager)
- [7. PolicyConfig](#7-policyconfig)
- [8. MyDeviceAdminReceiver](#8-mydeviceadminreceiver)
- [9. 状態と結果を表すデータクラス](#9-状態と結果を表すデータクラス)
- [10. 画面・リソース・Manifest](#10-画面リソースmanifest)
- [11. スレッド・状態保持・エラー処理](#11-スレッド状態保持エラー処理)
- [12. テスト用クラスとスクリプト](#12-テスト用クラスとスクリプト)
- [13. 変更する場合の入口](#13-変更する場合の入口)

関連資料：[作業手順書](SETUP_GUIDE.md)／[README](../README.md)／[検証結果](../VERIFICATION.md)

## 1. システム概要

端末単体で動くDevice Policy Controller（DPC）です。ADBでDevice Ownerに登録した後、ユーザーのボタン操作でAndroidの端末管理APIを呼び出します。管理サーバーや専用のバックグラウンド監視サービスはありません。

| 項目 | 設定 |
| --- | --- |
| アプリ名 | FocusDPC |
| アプリID／名前空間 | `io.github.focusdpc` |
| バージョン | `0.1.0-beta`／versionCode 1 |
| 対応OS | Android 10（API 29）以上 |
| compileSdk／targetSdk | 36／36 |
| 言語・JVMターゲット | Kotlin 2.2.21／Java 17 |
| ビルド | Gradle 8.13／Android Gradle Plugin 8.13.2／Build Tools 36.0.0 |
| UI | 標準ActivityとXMLレイアウト |
| 非同期処理 | Javaの単一スレッドExecutorとAndroidのmainExecutor |
| 追加の実行時ライブラリ | Kotlin標準ライブラリ |

実行する設定は4項目です。

| 設定 | Androidの主なAPI |
| --- | --- |
| NextDNSをPrivate DNSの指定ホストに設定 | `setGlobalPrivateDnsModeSpecifiedHost()` |
| Private DNSの変更を禁止 | `addUserRestriction(DISALLOW_CONFIG_PRIVATE_DNS)` |
| 新しいアプリのインストールを禁止 | `addUserRestriction(DISALLOW_INSTALL_APPS)` |
| FocusDPC自身のアンインストールを禁止 | `setUninstallBlocked(..., true)` |

Device Ownerの登録・解除はADBの担当です。アプリ内の制限解除はOwner登録を残し、Private DNSは「自動」へ戻します。ADB禁止、出荷時リセット禁止、端末消去は実装していません。

## 2. クラス構成と処理の流れ

### 2.1 関係図

~~~mermaid
flowchart TD
    A["MainActivity<br/>画面とボタン"] --> B["FocusDpcApplication<br/>処理の保持"]
    B --> C["PolicyTaskRunner<br/>非同期実行と状態通知"]
    C --> D["PolicyManager<br/>端末管理API呼び出し"]
    D --> E["Android<br/>DevicePolicyManager / UserManager"]
    D --> F["PolicyConfig<br/>ホスト名と形式検証"]
    C --> G["PolicyUiState<br/>画面表示用データ"]
    G --> A
    H["ADB<br/>Owner登録・解除"] --> E
    E -. 管理者コンポーネント .-> I["MyDeviceAdminReceiver"]
~~~

### 2.2 ファイルと役割

Kotlin本体は7ファイル、10個のクラス／objectで構成します。テスト用クラスは別に1個あります。

| ファイル | クラス／object | 役割 |
| --- | --- | --- |
| [MainActivity.kt](../app/src/main/java/io/github/focusdpc/MainActivity.kt) | MainActivity | 画面の作成、操作の受付、状態と結果の表示 |
| [FocusDpcApplication.kt](../app/src/main/java/io/github/focusdpc/FocusDpcApplication.kt) | FocusDpcApplication | 画面回転をまたいでPolicyTaskRunnerを保持 |
| [PolicyTaskRunner.kt](../app/src/main/java/io/github/focusdpc/PolicyTaskRunner.kt) | PolicyTaskRunner | ワーカースレッドで処理し、結果を画面へ通知 |
| [PolicyManager.kt](../app/src/main/java/io/github/focusdpc/PolicyManager.kt) | PolicyManager | 状態の読取りとポリシーの適用・解除 |
| [PolicyConfig.kt](../app/src/main/java/io/github/focusdpc/PolicyConfig.kt) | PolicyConfig | NextDNSホスト名と入力形式の検証 |
| [MyDeviceAdminReceiver.kt](../app/src/main/java/io/github/focusdpc/MyDeviceAdminReceiver.kt) | MyDeviceAdminReceiver | Androidが端末管理者として参照する登録先 |
| [PolicyState.kt](../app/src/main/java/io/github/focusdpc/PolicyState.kt) | PolicyState／PolicyStepResult／PolicyActionResult／PolicyUiState | 状態・操作結果・画面表示用データ |

### 2.3 ボタンを押してから結果が出るまで

1. MainActivityがPolicyTaskRunnerへ依頼する。
2. PolicyTaskRunnerがメインスレッドで処理中フラグを立て、画面へ通知する。
3. 単一のワーカースレッドでPolicyManagerの操作を実行する。
4. 同じワーカースレッドでAndroidから現在の状態を読み直す。
5. メインスレッドで状態と操作結果を通知する。
6. MainActivityが表示を更新し、ボタンを再び操作可能にする。

状態更新だけの場合は手順3の変更操作を行いません。適用・解除の戻り値をそのまま「現在の状態」に流用せず、Androidの実際の状態を別に読みます。

## 3. MainActivity

ソース：[MainActivity.kt](../app/src/main/java/io/github/focusdpc/MainActivity.kt)

### 3.1 このクラスでしていること

メイン画面を表示する標準Activityです。ボタン操作をPolicyTaskRunnerへ渡し、通知されたPolicyUiStateを表示します。端末管理APIは直接呼びません。

画面には5つの状態、設定先ホスト名、現在のホスト名、処理結果、読取りエラーを表示します。処理中は3つのボタンを無効化し、進捗表示を出します。Android 15以上ではシステムバーや画面の切り欠きに合わせて余白を調整します。

### 3.2 private以外のメソッド

ライフサイクルメソッドはAndroidが呼び出します。

| メソッド | 入力 | 戻り値 | 主な処理／出力・副作用 |
| --- | --- | --- | --- |
| `onCreate(savedInstanceState: Bundle?)` | Androidから渡される保存状態。初回はnullの場合がある | Unit | レイアウトを読み込み、ホスト名を表示してボタン操作を登録。解除ボタンでは確認ダイアログを表示。独自の状態復元にはBundleを使わず、Application側の状態を参照する |
| `onStart()` | なし | Unit | 状態の購読を開始。現在の状態がすぐ通知され、表示へ反映される |
| `onResume()` | なし | Unit | 最新状態の読取りを依頼。Android設定画面やADBで変更した内容も再確認する。既に処理中なら依頼は受け付けない |
| `onStop()` | なし | Unit | 状態の購読を解除。表示されていないActivityを通知先として保持し続けるのを防ぐ |

### 3.3 ボタンの動作

| ボタン | 依頼先 | 結果 |
| --- | --- | --- |
| ポリシーを適用 | `PolicyTaskRunner.applyPolicies()` | 適用結果と最新状態を表示 |
| 状態を更新 | `PolicyTaskRunner.refresh()` | 最新状態を表示。直前の操作結果は保持 |
| 制限を解除（β版） | 確認後に `PolicyTaskRunner.clearBetaPolicies()` | 解除結果と最新状態を表示。ダイアログをキャンセルした場合は変更しない |

## 4. FocusDpcApplication

ソース：[FocusDpcApplication.kt](../app/src/main/java/io/github/focusdpc/FocusDpcApplication.kt)

### 4.1 このクラスでしていること

アプリのプロセスに対応するApplicationです。Activityより長く生存し、PolicyTaskRunnerを1つ保持します。画面回転でActivityが作り直されても、処理中のフラグ・操作結果・Executorを共有するための置き場所です。

### 4.2 公開プロパティ

| 名前 | 型 | 入力／生成タイミング | 出力・用途 |
| --- | --- | --- | --- |
| `policyTasks` | PolicyTaskRunner | 最初に参照したとき、このApplicationをContextとして生成 | 以降は同じPolicyTaskRunnerを返す。`by lazy` による遅延初期化 |

独自に定義した非privateメソッドはありません。`onCreate()` などの独自オーバーライドもありません。

保持範囲はプロセスの生存中です。プロセス終了や端末再起動をまたいで操作結果を保存する仕組みではありません。

## 5. PolicyTaskRunner

ソース：[PolicyTaskRunner.kt](../app/src/main/java/io/github/focusdpc/PolicyTaskRunner.kt)

### 5.1 このクラスでしていること

画面とPolicyManagerの間に入り、処理の非同期実行、二重実行防止、状態の通知を担当します。DNS設定の接続確認が画面を止めないよう、端末管理処理を単一のワーカースレッドで実行します。

状態・購読者の管理はメインスレッドで行います。処理完了後は `Context.mainExecutor` でメインスレッドへ戻し、購読者へ通知します。

### 5.2 コンストラクター

`PolicyTaskRunner(context: Context)`

| 入力 | 生成されるもの |
| --- | --- |
| アプリのContext。内部ではapplicationContextを使用 | PolicyManager、単一スレッドExecutor、空の購読者集合、初期PolicyUiState |

初期状態は `status = null`、`busy = false`、`actionResult = null` です。

### 5.3 private以外のメソッド

**すべてメインスレッドから呼びます。** 戻り値はすべてUnitであり、適用結果そのものを直接返すメソッドではありません。

| メソッド | 入力 | 戻り値 | 主な処理／出力・副作用 |
| --- | --- | --- | --- |
| `observe(listener: (PolicyUiState) -> Unit)` | 状態を受け取る関数 | Unit | 購読者を登録し、その場で現在の状態を1回通知。以後、状態の変化をメインスレッドで通知する |
| `stopObserving(listener: (PolicyUiState) -> Unit)` | 登録時と同じ関数 | Unit | 購読者を解除し、以後の通知対象から外す |
| `refresh()` | なし | Unit | 変更操作を行わず、状態を非同期で読み直す。直前の操作結果は保持して通知する |
| `applyPolicies()` | なし | Unit | PolicyManagerの適用処理を非同期実行し、操作結果と再取得した状態を通知する |
| `clearBetaPolicies()` | なし | Unit | PolicyManagerの解除処理を非同期実行し、操作結果と再取得した状態を通知する |

### 5.4 実行のルール

- `busy = true` の間に来たrefresh／適用／解除の依頼は、キューに積まず、その場で無視します。
- 受付時にbusyをtrueとして通知し、完了時にfalseとして通知します。
- 画面回転後も同じRunnerを使うので、同じ操作を二重に開始しません。
- 操作中の予期しないRuntimeExceptionは、汎用の失敗結果へ変換します。その後、状態を読み直します。
- メインスレッド以外から公開メソッドを呼ぶと、スレッド条件のチェックでIllegalStateExceptionになります。

## 6. PolicyManager

ソース：[PolicyManager.kt](../app/src/main/java/io/github/focusdpc/PolicyManager.kt)

### 6.1 このクラスでしていること

アプリ本体で端末管理APIを呼ぶ唯一のクラスです。Device Owner確認、現在の状態の取得、4項目の適用、β版の制限解除を担当します。

AndroidのDevicePolicyManagerとUserManagerを使用します。画面の部品やActivityは保持しません。

### 6.2 コンストラクター

`PolicyManager(context: Context, nextDnsHostname: String = PolicyConfig.NEXTDNS_HOSTNAME)`

| 入力 | 意味 |
| --- | --- |
| `context` | システムサービス取得用。内部ではapplicationContextを使用 |
| `nextDnsHostname` | 設定・比較に使うホスト名。省略時はPolicyConfigの定数。テストでは失敗用ホストを注入できる |

出力はPolicyManagerのインスタンスです。生成時にDevicePolicyManager、UserManager、MyDeviceAdminReceiverのComponentNameを準備します。必要なサービスが取得できない場合は生成に失敗します。

### 6.3 `readStatus(): PolicyState`

| 項目 | 内容 |
| --- | --- |
| 入力 | 引数なし。Androidの現在の状態と、コンストラクターで渡した設定先ホスト名を参照 |
| 戻り値 | PolicyState |
| 端末への変更 | なし |
| 呼出しスレッド | 本アプリではPolicyTaskRunnerのワーカースレッド。メソッド自体にスレッド制限チェックはない |

主な処理：

1. 自分がDevice Ownerかを調べる。
2. OwnerならPrivate DNSのモード・ホスト名を取得する。
3. DNSが「指定ホスト名」モードで、設定先と実際のホスト名が一致すればNextDNS設定済みと判定する。比較は大文字・小文字を区別しない。
4. DNS変更禁止とアプリインストール禁止の実効状態をUserManagerで取得する。他の管理者による制限も表示に反映される。
5. Ownerなら、自分のアンインストール禁止状態を取得する。
6. 各読取りのRuntimeExceptionを捕捉し、取得できない値はnull、説明文はreadErrorsに格納する。重複する説明文はまとめる。

Ownerでなければ、権限が必要なDNSモード／ホスト名と自身のアンインストール禁止は取得せず、未確認として返します。DNSのモードが不明な場合も「未確認」です。

DNSが「自動」など別モードならnextDnsConfiguredはfalseです。この判定は設定値の確認であり、NextDNSへの疎通監視やフィルタリング確認はしません。

### 6.4 `applyPolicies(): PolicyActionResult`

| 項目 | 内容 |
| --- | --- |
| 入力 | 引数なし。設定先ホスト名、管理者コンポーネント、自分のパッケージ名を使用 |
| 戻り値 | 全体の概要と項目別の成功・失敗を含むPolicyActionResult |
| 端末への変更 | Private DNS設定、DNS変更禁止、インストール禁止、自身のアンインストール禁止 |
| 呼出しスレッド | 必ずワーカースレッド。メインスレッドからはIllegalStateException |

処理順序：

1. Device Ownerか確認する。未登録なら「このアプリはDevice Ownerではありません」を返して終了する。Owner確認自体が失敗した場合も、説明結果を返して終了する。
2. ホスト名の形式を検証し、不正ならDNSの失敗結果を作る。
3. 形式が正しければPrivate DNS設定APIを呼ぶ。DNS over TLSへの接続確認を伴う。
4. APIの成功コードに加え、実際のDNSモードとホスト名を再取得して反映を確認する。
5. DNS設定の成功を確認した場合だけ、DNS変更禁止を追加する。失敗時はこの追加をスキップする。
6. DNSの成否にかかわらず、アプリインストール禁止を試みる。
7. DNSの成否にかかわらず、自分のアンインストール禁止を試みる。
8. 全項目の結果をまとめて返す。

Owner確認を通った場合、stepsは「NextDNS → DNS変更禁止 → インストール禁止 → DPCアンインストール禁止」の4件です。スキップしたDNS変更禁止も、`success = false` と説明文を持つ1件として入ります。全件成功なら完了、それ以外は一部失敗の概要になります。

**一括トランザクションではありません。** 途中で失敗しても他の項目を続け、既に成功した変更を自動で巻き戻しません。DNS失敗時に、既存のDNS変更禁止を解除する処理もありません。

### 6.5 `clearBetaPolicies(): PolicyActionResult`

| 項目 | 内容 |
| --- | --- |
| 入力 | 引数なし。自分の管理者コンポーネントとパッケージ名を使用 |
| 戻り値 | 全体の概要と4項目の解除結果を含むPolicyActionResult |
| 端末への変更 | 自分が設定した3つの制限を解除し、Private DNSを「自動」へ変更 |
| 呼出しスレッド | 必ずワーカースレッド。メインスレッドからはIllegalStateException |

処理順序：

1. Device Ownerか確認する。未登録・確認失敗なら説明結果を返して終了する。
2. 自分が設定したDNS変更禁止を解除する。
3. 自分が設定したアプリインストール禁止を解除する。
4. 自分のアンインストール禁止を解除する。
5. `setGlobalPrivateDnsModeOpportunistic()` でDNSを「自動」にし、戻りコードを確認する。
6. 各結果をまとめて返す。

1つの解除が失敗しても残りを試みます。Device Owner登録自体は解除しません。以前のDNS設定を保存していないため、変更前のホスト名やモードを復元する機能ではありません。

### 6.6 エラー結果の扱い

| 状況 | 結果 |
| --- | --- |
| ホスト名が不正／初期値のまま | NextDNSのID未設定・ホスト名不正として返す |
| DNS APIがHOST_NOT_SERVINGを返す | ホスト名・ネットワーク・TCP 853の確認を促す |
| DNS APIがFAILURE_SETTINGを返す | 端末の管理状態と追加ユーザーの確認を促す |
| DNS APIが未知のエラーコードを返す | コードを含む説明文を返す |
| 設定後のモード・ホスト名が一致しない | DNS反映未確認として返し、DNS変更禁止を新規追加しない |
| 項目処理のSecurityException | 権限エラーとして返す |
| 項目処理のIllegalArgumentException | 設定値エラーとして返す |
| その他のRuntimeException | 例外メッセージ、または汎用エラーとして返す |

通常の項目エラーはPolicyStepResultへ変換します。呼出しスレッドの誤りなど、前提条件違反まで必ず結果へ変換する設計ではありません。

## 7. PolicyConfig

ソース：[PolicyConfig.kt](../app/src/main/java/io/github/focusdpc/PolicyConfig.kt)

### 7.1 このobjectでしていること

設定先ホスト名と、DNS APIへ渡す前の形式検証をまとめます。Kotlinのobjectなので、自分でインスタンスを作らず `PolicyConfig.名前` で参照します。

### 7.2 公開定数

| 名前 | 型・初期値 | 用途 |
| --- | --- | --- |
| `NEXTDNS_HOSTNAME` | String／`YOUR_ID.dns.nextdns.io` | アプリの設定先。実際のDNS-over-TLSホスト名全体へ変更し、再ビルドする |

画面から変更・保存する機能はありません。

### 7.3 `isUsableHostname(hostname: String): Boolean`

| 項目 | 内容 |
| --- | --- |
| 入力 | DNSホスト名の候補文字列 |
| 戻り値 | 形式条件に合えばtrue、合わなければfalse |
| 端末への変更・通信 | なし |

検証条件：

- 全体が1〜253文字。
- `.` で分割して2ラベル以上ある。
- 各ラベルが1〜63文字。
- ラベル内の文字はASCIIの英字・数字・ハイフン。
- ラベルの先頭・末尾は英字か数字。

例：`abcdef.dns.nextdns.io` は形式上true、`YOUR_ID.dns.nextdns.io` はアンダースコアがあるのでfalse、`https://...` や空白を含む文字列もfalseです。

このメソッドは**DNS名の形式だけ**を見ます。NextDNSの実在ID、所有権、`.dns.nextdns.io` という接尾辞への限定、TLS疎通は検証しません。また、IPv4のような数字のドット区切り文字列を明示的に除外する処理もありません。運用上はNextDNSの画面に表示されたホスト名を指定します。

## 8. MyDeviceAdminReceiver

ソース：[MyDeviceAdminReceiver.kt](../app/src/main/java/io/github/focusdpc/MyDeviceAdminReceiver.kt)

### 8.1 このクラスでしていること

Android標準のDeviceAdminReceiverを継承し、端末管理者コンポーネントとして登録される入口です。

登録名：

~~~text
io.github.focusdpc/.MyDeviceAdminReceiver
~~~

ADBのOwner登録・解除と、PolicyManagerのComponentNameの両方で使用します。ManifestにReceiverと管理者メタデータを宣言しています。

### 8.2 メソッド

独自に定義・オーバーライドしたメソッドはありません。標準のReceiver動作を使います。

登録通知でポリシーを自動適用する処理、削除を妨げる処理、Owner自動再登録はありません。適用は画面ボタンから行います。

## 9. 状態と結果を表すデータクラス

ソース：[PolicyState.kt](../app/src/main/java/io/github/focusdpc/PolicyState.kt)

4つともデータの受渡し用です。コンストラクターの入力がそのまま読み取り専用プロパティになります。独自に定義した非privateメソッドはありません。Kotlinが生成する `copy()`、`componentN()`、`equals()` などの標準動作は個別説明を省略します。

### 9.1 PolicyState

**役割：** Androidから読み取った現在の状態を、PolicyManagerから画面へ渡します。

| プロパティ／コンストラクター入力 | 型 | 意味 |
| --- | --- | --- |
| `deviceOwner` | Boolean? | 自分がDevice Ownerか |
| `nextDnsConfigured` | Boolean? | 指定ホスト名モードで、設定先のホスト名と一致するか |
| `privateDnsChangesBlocked` | Boolean? | DNS変更禁止の実効状態 |
| `appInstallationBlocked` | Boolean? | アプリインストール禁止の実効状態 |
| `selfUninstallBlocked` | Boolean? | FocusDPC自身のアンインストール禁止状態 |
| `currentDnsHostname` | String? | Androidから取得した現在のDNSホスト名。未指定・未取得ならnullになり得る |
| `readErrors` | List<String> | 読取り失敗の説明文。既定値は空リスト |

Boolean?は、true＝有効／設定済み、false＝無効／未設定、null＝権限不足・未取得・読取り失敗などによる未確認です。nullをfalseとして扱わず、画面にも「未確認」と表示します。

### 9.2 PolicyStepResult

**役割：** 適用・解除の1項目分の結果を表します。

| プロパティ／コンストラクター入力 | 型 | 意味 |
| --- | --- | --- |
| `label` | String | 「NextDNS」「アプリインストール禁止」などの項目名 |
| `success` | Boolean | 項目が成功したか。スキップもfalse |
| `detail` | String | 「適用済み」「解除済み」、エラー理由など |

### 9.3 PolicyActionResult

**役割：** 適用または解除という、1回の操作全体の結果を表します。

| プロパティ／コンストラクター入力 | 型 | 意味 |
| --- | --- | --- |
| `summary` | String | 全成功、一部失敗、Owner未登録などの概要 |
| `steps` | List<PolicyStepResult> | 項目別結果。既定値は空リスト |

Owner未登録など、項目処理を開始しなかった場合は概要だけを返し、stepsは空です。

### 9.4 PolicyUiState

**役割：** 画面が必要とする情報を1つにまとめ、Runnerから通知します。

| プロパティ／コンストラクター入力 | 型・既定値 | 意味 |
| --- | --- | --- |
| `status` | PolicyState?／null | 最後に読み取った端末状態 |
| `busy` | Boolean／false | 処理中か。ボタン無効化と進捗表示に使う |
| `actionResult` | PolicyActionResult?／null | 最後の適用・解除結果。状態更新だけなら前の結果を残す |

操作結果と現在の状態は別のデータです。たとえば前回の成功文が残っていても、現在値は状態更新で別に確認します。

## 10. 画面・リソース・Manifest

### 10.1 画面と文言

| ファイル | 内容 |
| --- | --- |
| [activity_main.xml](../app/src/main/res/layout/activity_main.xml) | ScrollView内に状態欄、ホスト名、3つのボタン、進捗表示、操作結果を配置 |
| [strings.xml](../app/src/main/res/values/strings.xml) | 画面・ダイアログ・操作結果・エラーの日本語文言 |
| [styles.xml](../app/src/main/res/values/styles.xml) | 標準Material系のライトテーマ |
| [colors.xml](../app/src/main/res/values/colors.xml) | 文字・背景・有効状態などの色 |
| `res/drawable/` | アイコンとカード・注意書きの背景 |

### 10.2 Manifestと管理XML

| 設定 | 目的 |
| --- | --- |
| [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) の `INTERNET` | DNS設定APIのTLS接続確認に必要。通常権限なので実行時ダイアログは不要 |
| `android.software.device_admin` | 端末管理機能が必要なアプリであることを宣言 |
| `android:name=".FocusDpcApplication"` | Applicationに処理を保持する |
| `android:testOnly="true"` | ADBのtestOnly管理アプリ解除経路を維持。releaseでも残る。インストールには `-t` が必要 |
| `android:allowBackup="false"` とバックアップXML | アプリデータのバックアップを無効化。復元でOwner登録を再現する設計ではない |
| MainActivityのMAIN／LAUNCHER | ランチャーからの起動 |
| Receiverの `BIND_DEVICE_ADMIN` | 管理用通知を送れる呼出し元をシステム権限で制限 |
| [device_admin.xml](../app/src/main/res/xml/device_admin.xml) | 管理者メタデータ。uses-policiesは空で、端末消去などの従来型管理ポリシーは宣言しない |

バックアップ用XMLは [backup_rules.xml](../app/src/main/res/xml/backup_rules.xml) と [data_extraction_rules.xml](../app/src/main/res/xml/data_extraction_rules.xml) です。

## 11. スレッド・状態保持・エラー処理

### 11.1 スレッドの分担

| スレッド | 担当 |
| --- | --- |
| メインスレッド | Activity操作、Runnerの公開メソッド、状態・購読者管理、画面への通知 |
| 単一のワーカースレッド | ポリシー適用・解除、現在状態の取得 |

DNS APIの接続確認には時間がかかるため、変更処理はメインスレッドで実行できないようチェックします。ExecutorはApplicationから保持され、Activityを閉じても実行中の処理は続きます。操作のキャンセルAPIはありません。

### 11.2 保存されるもの・再取得するもの

| 情報 | 保存先・寿命 |
| --- | --- |
| NextDNSの設定先 | ソースの定数としてAPKに埋込み。変更には再ビルド |
| 適用したDNS・制限・Owner登録 | Android側が管理。アプリ再起動後はAPIから取得 |
| busy・最後の状態・最後の操作結果 | Application内のメモリー。プロセス終了で失われる |
| 変更前のDNS設定 | 保存しない。解除では「自動」へ変更 |
| 操作履歴 | 独自のファイル／DBには保存しない。例外はAndroidのLogへ記録 |

再起動でアプリが自動適用するReceiverやServiceはありません。設定の維持はAndroidが管理するポリシーに依存するため、実機で再起動後の状態を確認します。

### 11.3 部分成功の意味

DNSと3つの制限は、それぞれ別のAPI呼出しです。全体の概要だけでなくstepsとreadStatusの値を確認します。

例：初期値のまま適用すると、NextDNSは失敗、DNS変更禁止はスキップ、インストール禁止と自身のアンインストール禁止は適用され得ます。解除も項目別であり、失敗項目があれば個別結果を確認します。

## 12. テスト用クラスとスクリプト

### 12.1 PolicyInstrumentation

ソース：[PolicyInstrumentation.kt](../app/src/androidTest/java/io/github/focusdpc/PolicyInstrumentation.kt)

**役割：** Android標準Instrumentationを使い、実際の端末管理APIを検証します。通常のアプリAPKには含まれず、テストAPK側に入ります。専用のテストライブラリは使用しません。

| メソッド | 入力 | 戻り値 | 主な処理／出力 |
| --- | --- | --- | --- |
| `onCreate(arguments: Bundle?)` | `scenario` というキーを持つBundle。省略時は `non_owner` | Unit | シナリオを選択し、Instrumentationの実行を開始する |
| `onStart()` | 引数なし。選択済みscenarioと対象アプリのContextを使用 | Unit | エミュレータであることを確認して、対象の検証を実行。結果をBundleの `stream` に入れ、`finish()` で通知する |

`Build.HARDWARE` が `ranchu` または `goldfish` でなければ、ポリシーを変更せず失敗させます。処理中のExceptionは失敗結果へ変換します。

| シナリオ | 主な確認 |
| --- | --- |
| `hostname_validation` | INTERNET権限、初期値・URL・空白などの拒否、正しい形式の受入れ |
| `non_owner` | 未登録時の状態、適用・解除の未登録メッセージ、権限が必要な状態のnull |
| `owner_apply` | 適用結果4件、制限反映、初期値の扱い、ADB・出荷時リセットを禁止していないこと |
| `owner_repeat` | 同じ適用検証を再度実行し、再適用の動作を確認 |
| `owner_dns_failure` | 予約ドメイン `focusdpc-test.invalid` を注入し、実際のDNS接続失敗と他の制限の適用を確認 |
| `owner_clear` | 制限解除、DNS自動、Owner登録が残ること |

成功時は `FOCUSDPC_TEST_PASS: シナリオ名` と `Activity.RESULT_OK`、失敗時は `FOCUSDPC_TEST_FAIL: シナリオ名` と例外内容、`Activity.RESULT_CANCELED` を返します。不明なシナリオも失敗です。

### 12.2 emulator_smoke_test.py

ソース：[emulator_smoke_test.py](../scripts/emulator_smoke_test.py)

**役割：** PC側からADBを実行し、APK導入、Owner登録、Instrumentation呼出し、解除・後片付けをまとめて行います。実機では使用しません。

| 関数 | 入力 | 戻り値 | 主な処理／出力 |
| --- | --- | --- | --- |
| `main() -> int` | コマンドライン引数 `--serial`。既定値は `emulator-5554` | 成功0、検証中の失敗1。引数不正はargparseが終了させる | 端末条件とAPKを確認し、各検証を実行。標準出力／標準エラーへ経過と結果を表示する |

`main` 内部だけで使う補助関数の個別説明は省略します。

事前条件は、シリアルが `emulator-` で始まること、hardwareがranchu/goldfishであること、起動完了済み、既存Device Ownerがないことです。アプリAPKとテストAPKは先にビルドしておく必要があります。

6種類のシナリオを、正常時は再確認と後片付けを含め9回実行します。`am instrument` の終了コードだけに頼らず、成功マーカーも確認します。最後に、自分で登録したOwnerだけを解除し、テストAPKを削除します。本体アプリは未登録状態で残します。

切断などで解除処理が完了しない場合は、作業手順書のADB解除を使います。実際のNextDNS IDでの接続成功・フィルタリングや実機固有の動作は、手動確認の担当です。

## 13. 変更する場合の入口

| 変更したい内容 | 主に確認する場所 |
| --- | --- |
| 自分のNextDNSホスト名 | PolicyConfig.NEXTDNS_HOSTNAME。保存後に再ビルド・上書き |
| DNS名の形式検証 | PolicyConfig.isUsableHostname()と対応テスト |
| 制限の種類・適用順・解除順 | PolicyManager。適用だけでなく解除・部分失敗も確認 |
| 非同期実行・状態通知 | PolicyTaskRunner |
| 表示内容・ボタン操作 | MainActivity、activity_main.xml、strings.xml |
| 表示に必要なデータの追加 | PolicyState.kt、PolicyManager、MainActivity |
| パッケージ／管理者クラス名 | Gradle・Manifest・Kotlin・ADB手順・テストの登録名を一緒に確認 |

仕様を変更する場合も、登録 → 解除テスト → 再登録 → 適用 → 制限解除 → Owner解除を確認します。現在の `testOnly` とADB復旧経路を前提にした手順書と、実装の整合性を保ってください。
