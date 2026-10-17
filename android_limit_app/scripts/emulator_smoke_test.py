#!/usr/bin/env python3
"""使い捨てのAndroid Emulatorで、FocusDPCの登録・制限・解除を検証します。"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


PACKAGE = "io.github.focusdpc"
ADMIN = f"{PACKAGE}/.MyDeviceAdminReceiver"
TEST_PACKAGE = f"{PACKAGE}.test"
RUNNER = f"{TEST_PACKAGE}/{PACKAGE}.PolicyInstrumentation"
PROJECT = Path(__file__).resolve().parent.parent
PLAY_STORE_PACKAGE = "com.android.vending"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554", help="ADBのエミュレータ識別子")
    args = parser.parse_args()

    # 実機を誤ってDevice Ownerにしないため、シリアルと端末情報の両方を確認します。
    if not args.serial.startswith("emulator-"):
        parser.error("実機には実行できません。emulator-で始まるシリアルを指定してください。")

    def adb(*command: str, require_success: bool = True) -> str:
        result = subprocess.run(
            ["adb", "-s", args.serial, *command],
            capture_output=True, text=True, timeout=180, check=False,
        )
        output = result.stdout + result.stderr
        if require_success and result.returncode != 0:
            raise RuntimeError(f"ADBの実行に失敗: {' '.join(command)}\n{output}")
        return output

    def run_scenario(name: str) -> None:
        print(f"シナリオ実行: {name}", flush=True)
        output = adb("shell", "am", "instrument", "-w", "-e", "scenario", name, RUNNER)
        print(output, flush=True)
        # am instrumentはテスト失敗でも終了コード0の場合があるため、成功マーカーも確認します。
        if f"FOCUSDPC_TEST_PASS: {name}" not in output:
            raise RuntimeError(f"テストに失敗: {name}")
        if name == "owner_clear" and store_fixture_installed:
            visible = adb("shell", "pm", "list", "packages", PLAY_STORE_PACKAGE).splitlines()
            if f"package:{PLAY_STORE_PACKAGE}" not in visible:
                raise RuntimeError("全解除後もストア代役APKが非表示のままです。")

    def install_apk(apk: Path) -> None:
        print(f"APK導入: {apk.name}", flush=True)
        output = adb("install", "-t", "-r", str(apk))
        print(output, flush=True)
        if "Success" not in output:
            raise RuntimeError("APKをインストールできませんでした。")

    def install_store_fixture() -> None:
        # AOSPイメージには本物のPlayストアがありません。空の代役APKで非表示APIを検証します。
        # 既存のPlayストアは置換しません。呼出し前に実機拒否とパッケージ不在を確認します。
        adb_path = shutil.which("adb")
        if not adb_path:
            raise RuntimeError("ADBが見つかりません。Platform-ToolsをPATHへ追加してください。")
        sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        sdk = Path(sdk_value) if sdk_value else Path(adb_path).resolve().parent.parent
        build_tools = sdk / "build-tools/36.0.0"
        aapt = build_tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
        signer = build_tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
        user_android = Path(os.environ.get("ANDROID_USER_HOME", str(Path.home() / ".android")))
        keys = [user_android / "debug.keystore", Path.home() / ".android/debug.keystore"]
        keystore = next((key for key in keys if key.is_file()), None)
        if not aapt.is_file() or not signer.is_file() or keystore is None:
            raise RuntimeError("SDK Build Tools 36.0.0と、アプリのビルドで作成されるdebug.keystoreが必要です。")
        scratch = PROJECT.parent / "work"
        scratch.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="store-fixture-", dir=scratch) as directory:
            temp = Path(directory)
            manifest = temp / "AndroidManifest.xml"
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                'package="com.android.vending" android:versionCode="1" android:versionName="test">'
                '<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="29"/>'
                '<application android:hasCode="false" android:allowBackup="false" '
                'android:label="FocusDPC test fixture">'
                '<activity android:name="android.app.Activity" android:exported="true">'
                '<intent-filter><action android:name="android.intent.action.MAIN"/>'
                '<category android:name="android.intent.category.LAUNCHER"/></intent-filter>'
                '</activity></application></manifest>', encoding="utf-8")
            apk = temp / "store-fixture.apk"
            subprocess.run([str(aapt), "link", "--manifest", str(manifest),
                            "-I", str(sdk / "platforms/android-36/android.jar"),
                            "-o", str(apk)], capture_output=True, text=True, check=True)
            subprocess.run([str(signer), "sign", "--ks", str(keystore),
                            "--ks-pass", "pass:android", "--key-pass", "pass:android", str(apk)],
                           capture_output=True, text=True, check=True)
            install_apk(apk)

    owner_registered = False
    tests_installed = False
    store_fixture_installed = False
    test_failure = None
    try:
        print("テスト用AVDの準備確認", flush=True)
        hardware = adb("shell", "getprop", "ro.hardware").strip()
        if hardware not in {"ranchu", "goldfish"}:
            raise RuntimeError(f"Android Emulatorとして確認できません: {hardware}")
        if adb("shell", "getprop", "sys.boot_completed").strip() != "1":
            raise RuntimeError("エミュレータの起動が完了していません。")
        if "Device Owner" in adb("shell", "dumpsys", "device_policy"):
            raise RuntimeError("既にDevice Ownerがあります。新しいテスト用AVDを使用してください。")

        app_apk = PROJECT / "app/build/outputs/apk/debug/app-debug.apk"
        test_apk = PROJECT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        for apk in (app_apk, test_apk):
            if not apk.is_file():
                raise RuntimeError("先に ./gradlew assembleDebug assembleDebugAndroidTest を実行してください。")
            install_apk(apk)
            if apk == test_apk:
                tests_installed = True

        run_scenario("hostname_validation")
        run_scenario("non_owner")
        if f"package:{PLAY_STORE_PACKAGE}" not in adb("shell", "pm", "list", "packages", "-u", PLAY_STORE_PACKAGE).splitlines():
            # 途中でタイムアウトしても、このAVDに存在しなかった代役の削除を試みます。
            store_fixture_installed = True
            install_store_fixture()
            launch = adb("shell", "am", "start", "-W", "-n", f"{PLAY_STORE_PACKAGE}/android.app.Activity")
            if "Error" in launch or "Exception" in launch:
                raise RuntimeError(f"ストア代役APKの起動に失敗しました。\n{launch}")
        enrollment = adb("shell", "dpm", "set-device-owner", "--user", "0", ADMIN)
        print(enrollment, flush=True)
        if "Success" not in enrollment:
            raise RuntimeError("Device Ownerに登録できませんでした。")
        owner_registered = True

        run_scenario("owner_apply")
        if store_fixture_installed:
            if f"package:{PLAY_STORE_PACKAGE}" in adb("shell", "pm", "list", "packages", PLAY_STORE_PACKAGE):
                raise RuntimeError("ストア代役APKが非表示になっていません。")
            launch = adb("shell", "am", "start", "-W", "-n",
                         f"{PLAY_STORE_PACKAGE}/android.app.Activity", require_success=False)
            if "Error" not in launch and "Exception" not in launch:
                raise RuntimeError(f"非表示にしたストア代役APKを起動できてしまいました。\n{launch}")
        # ポリシー適用中にテストAPKを一度削除し、新規インストールと上書きの両方を実行します。
        print(adb("uninstall", TEST_PACKAGE), flush=True)
        tests_installed = False
        install_apk(test_apk)
        tests_installed = True
        install_apk(test_apk)
        # 自分自身の更新も、Device Ownerやポリシーを消さずにできることを確認します。
        install_apk(app_apk)
        print("FOCUSDPC_ADB_INSTALL_PASS: 新規・上書きの両方に成功", flush=True)
        run_scenario("owner_repeat")
        run_scenario("owner_migration")
        run_scenario("owner_clear")
        run_scenario("owner_dns_failure")
        run_scenario("owner_clear")
        if store_fixture_installed:
            print(adb("uninstall", PLAY_STORE_PACKAGE), flush=True)
            store_fixture_installed = False
            # ストアが最初からない端末でも、制限の適用が一部失敗にならないことを確認します。
            run_scenario("owner_apply")
            run_scenario("owner_clear")
    except Exception as exception:
        test_failure = exception
    finally:
        # このスクリプトが登録したOwnerだけを解除します。既存Ownerには触りません。
        if owner_registered:
            try:
                run_scenario("owner_clear")
            except Exception as exception:
                test_failure = test_failure or exception
            try:
                removal = adb("shell", "dpm", "remove-active-admin", "--user", "0", ADMIN)
                print(removal, flush=True)
                if "Success" not in removal:
                    raise RuntimeError("Device Owner解除に失敗しました。READMEの解除手順を確認してください。")
                run_scenario("non_owner")
            except Exception as exception:
                test_failure = test_failure or exception
        if tests_installed:
            print(adb("uninstall", TEST_PACKAGE, require_success=False), flush=True)
        if store_fixture_installed:
            print(adb("uninstall", PLAY_STORE_PACKAGE, require_success=False), flush=True)

    if test_failure:
        print(f"検証失敗: {test_failure}", file=sys.stderr)
        return 1
    print("すべての検証に成功しました。Device Ownerは解除済みです。", flush=True)
    print("FocusDPC本体は残しています。手動で起動して未登録状態を確認できます。", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
