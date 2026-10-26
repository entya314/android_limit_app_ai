#!/usr/bin/env python3
"""PowerShellと偽ADBで、PCツールの端末選択・成功／失敗判定を検証します（Linux用）。"""
import argparse
import codecs
import json
import os
from pathlib import Path
import subprocess
import tempfile


FAKE_ADB = r'''#!/usr/bin/env python3
import base64,json,os,sys
case=os.environ['FOCUSDPC_TEST_CASE']
args=sys.argv[1:]
with open(os.environ['FOCUSDPC_TEST_CALLS'],'a') as out: out.write(json.dumps(args)+'\n')
if args==['-d','get-serialno']:
    if case=='multiple':
        print('error: more than one device',file=sys.stderr); sys.exit(1)
    print('TEST_USB_PHONE')
    print('* daemon started successfully',file=sys.stderr)
    sys.exit(0)
assert args[:7]==['-s','TEST_USB_PHONE','shell','content','call','--user','0'],args
if case=='missing': print('Error: provider not found'); sys.exit(0)
if case=='bad_payload': print('Result: Bundle[{payload=not-json}]'); sys.exit(0)
if case=='native_failure': print('Error: transport disconnected',file=sys.stderr); sys.exit(1)
result={'success':True,'code':'OPENED','remainingSeconds':1800}
if case=='already': result.update(code='OPEN',remainingSeconds=120)
if case=='status': result.update(code='BLOCKED',remainingSeconds=0)
if case=='close': result.update(code='BLOCKED',remainingSeconds=0)
if case=='restore_failed': result.update(success=False,code='RESTORE_FAILED',remainingSeconds=0)
if case=='not_owner': result.update(success=False,code='NOT_OWNER',remainingSeconds=0)
if case=='unknown': result.update(code='UNKNOWN')
payload=base64.b64encode(json.dumps(result).encode()).decode()
print('Result: Bundle[{payload='+payload+'}]')
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pwsh', default='pwsh')
    args = parser.parse_args()
    script = Path(__file__).resolve().parent.parent / 'pc-tools/FocusDpcMaintenance.ps1'
    # Windows PowerShell 5.1はBOMなしUTF-8を日本語WindowsでCP932として読むため、
    # 日本語コメントの末尾で改行まで誤読し、関数の開始行が失われることがあります。
    assert script.read_bytes().startswith(codecs.BOM_UTF8), 'Keep the UTF-8 BOM for Windows PowerShell 5.1'
    encoding_check = r'''
$reader = [IO.StreamReader]::new($env:FOCUSDPC_TEST_SCRIPT, [Text.Encoding]::GetEncoding(932), $true)
try { $source = $reader.ReadToEnd() } finally { $reader.Dispose() }
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw ($parseErrors | Out-String) }
'''
    result = subprocess.run([args.pwsh, '-NoProfile', '-Command', encoding_check],
                            env=dict(os.environ, FOCUSDPC_TEST_SCRIPT=str(script)),
                            capture_output=True, text=True, timeout=30, check=False)
    assert result.returncode == 0, (result.stdout, result.stderr)
    print('PASS: Japanese Windows encoding with BOM detection')
    cases = [('open', 'Open', True), ('already', 'Open', True), ('status', 'Status', True),
             ('close', 'Close', True), ('multiple', 'Open', False), ('missing', 'Open', False),
             ('bad_payload', 'Open', False), ('native_failure', 'Open', False),
             ('restore_failed', 'Open', False), ('not_owner', 'Open', False), ('unknown', 'Open', False)]
    with tempfile.TemporaryDirectory(prefix='focusdpc-pc-test-') as directory:
        folder = Path(directory)
        adb = folder / 'adb'
        adb.write_text(FAKE_ADB)
        adb.chmod(0o755)
        calls = folder / 'calls.jsonl'
        for case, action, success in cases:
            calls.write_text('')
            env = dict(os.environ, PATH=str(folder) + os.pathsep + os.environ['PATH'],
                       FOCUSDPC_TEST_CASE=case, FOCUSDPC_TEST_CALLS=str(calls))
            result = subprocess.run([args.pwsh, '-NoProfile', '-File', str(script), '-Action', action],
                                    env=env, capture_output=True, text=True, timeout=30, check=False)
            assert (result.returncode == 0) == success, (case, result.stdout, result.stderr)
            commands = [json.loads(line) for line in calls.read_text().splitlines()]
            assert commands[0] == ['-d', 'get-serialno'], commands
            if case == 'multiple':
                assert len(commands) == 1, 'Multiple devices must not receive a modification command'
            else:
                assert commands[1][-1] == action.lower(), commands
            print('PASS:', case)
        # 時間をPC引数で指定しようとした場合はADBを呼ぶ前に拒否されることを確認します。
        calls.write_text('')
        result = subprocess.run([args.pwsh, '-NoProfile', '-File', str(script), '-Minutes', '999'],
                                env=env, capture_output=True, text=True, timeout=30, check=False)
        assert result.returncode != 0 and not calls.read_text()
        print('PASS: duration argument rejected')
    print('13 PC-tool cases passed.')


if __name__ == '__main__':
    main()
