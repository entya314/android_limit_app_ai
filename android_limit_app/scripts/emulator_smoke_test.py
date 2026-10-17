#!/usr/bin/env python3
"""使い捨てのAndroid Emulatorで、FocusDPCの登録・制限・解除を検証します。"""

import argparse
from pathlib import Path
import subprocess
import sys


PACKAGE = "io.github.focusdpc"
ADMIN = f"{PACKAGE}/.MyDeviceAdminReceiver"
TEST_PACKAGE = f"{PACKAGE}.test"
RUNNER = f"{TEST_PACKAGE}/{PACKAGE}.PolicyInstrumentation"
PROJECT = Path(__file__).resolve().parent.parent


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
        output = adb("shell", "am", "instrument", "-w", "-e", "scenario", name, RUNNER)
        print(output, flush=True)
        # am instrumentはテスト失敗でも終了コード0の場合があるため、成功マーカーも確認します。
        if f"FOCUSDPC_TEST_PASS: {name}" not in output:
            raise RuntimeError(f"テストに失敗: {name}")

    owner_registered = False
    tests_installed = False
    test_failure = None
    try:
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
            print(adb("install", "-t", "-r", str(apk)), flush=True)
            if apk == test_apk:
                tests_installed = True

        run_scenario("hostname_validation")
        run_scenario("non_owner")
        enrollment = adb("shell", "dpm", "set-device-owner", "--user", "0", ADMIN)
        print(enrollment, flush=True)
        if "Success" not in enrollment:
            raise RuntimeError("Device Ownerに登録できませんでした。")
        owner_registered = True

        run_scenario("owner_apply")
        run_scenario("owner_repeat")
        run_scenario("owner_clear")
        run_scenario("owner_dns_failure")
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

    if test_failure:
        print(f"検証失敗: {test_failure}", file=sys.stderr)
        return 1
    print("すべての検証に成功しました。Device Ownerは解除済みです。", flush=True)
    print("FocusDPC本体は残しています。手動で起動して未登録状態を確認できます。", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
