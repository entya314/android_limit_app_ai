Android Studioで動作するAndroidアプリ「FocusDPC」のβ版を作成してください。
目的：
自分自身のAndroidスマートフォンの利用制限を行うためのDevice Policy Controller（DPC）です。
企業向けMDMサーバーは不要で、端末単体で動作させます。
言語：
Kotlin
最低Android：
Android 10 / API 29以上
対象端末：
arrows We2
要件：
1. Device Ownerとして動作するDPCにする
2. NextDNSのPrivate DNSを設定する
DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost()を使用する。
NextDNS hostnameは現時点では定数として、
YOUR_ID.dns.nextdns.io
として実装する。
後から簡単に変更できる場所に定義する。
3. ユーザーがPrivate DNSを変更できないようにする
UserManager.DISALLOW_CONFIG_PRIVATE_DNSを使用する。
4. 新しいアプリのインストールを禁止する
UserManager.DISALLOW_INSTALL_APPSを使用する。
5. FocusDPC自身を通常のAndroid設定画面からアンインストールできないようにする
DevicePolicyManager.setUninstallBlocked()を使用する。
6. β版ではADB・開発者向けオプションは禁止しない
開発中に修正不能になるのを防ぐため。
ただし将来追加できるようPolicyManager内にコメントを入れておく。
7. β版では工場出荷状態へのリセットは禁止しない。
8. アプリ起動時に現在の状態を表示する。
画面には最低限以下を表示：
FocusDPC
Device Owner:
有効 / 無効
NextDNS:
設定済み / 未設定
Private DNS変更禁止:
有効 / 無効
アプリインストール禁止:
有効 / 無効
DPCアンインストール禁止:
有効 / 無効
「ポリシーを適用」ボタン
9. Device Ownerではない状態で「ポリシーを適用」を押した場合はクラッシュさせず、
「このアプリはDevice Ownerではありません」
と表示する。
10. NextDNS設定処理はメインスレッドで実行しない。
11. コードは機能ごとに分離する。
最低限、
MainActivity.kt
MyDeviceAdminReceiver.kt
PolicyManager.kt
AndroidManifest.xml
res/xml/device_admin.xml
の構成にする。
12. README.mdを作成する。
READMEには、
・Android Studioでのビルド方法
・エミュレータへのインストール方法
・ADBでDevice Ownerにするコマンド
・Device Owner確認コマンド
・テスト方法
・開発中にDISALLOW_DEBUGGING_FEATURESを有効にしてはいけない理由
を書く。
13. まずAndroid Emulatorで安全にテストできる構成にする。
14. 不要なライブラリは導入せず、可能な限りAndroid標準APIだけを使用する。
15. 現在のAndroid APIで非推奨になっている方法があれば使用せず、公式Android Developersの仕様を確認して実装する。
まずプロジェクト全体を作成してください。
その後、ビルドエラーがあれば修正し、可能な範囲で動作確認してください。
重要：
これはβ版なので、Device Owner解除不能化やADB禁止など、開発端末を初期化しなければ戻せなくなる機能はまだ追加しないでください。