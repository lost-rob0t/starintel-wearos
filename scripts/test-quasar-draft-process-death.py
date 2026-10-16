#!/usr/bin/env python3
"""Disposable-emulator-only acknowledged encrypted draft/process-death check; never submits."""
import hashlib
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'actor.starintel.quasar'
OUT = pathlib.Path('build/draft-storage')
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, check=True):
    return subprocess.run(['adb', *args], capture_output=True, check=check)

def text(*args, check=True):
    return adb(*args, check=check).stdout.decode().strip()

def nodes():
    text('shell', 'uiautomator', 'dump', '/sdcard/draft-process.xml')
    return ET.fromstring(text('shell', 'cat', '/sdcard/draft-process.xml'))

def tap(predicate):
    matches = [n for n in nodes().iter('node') if predicate(n)]
    assert matches, 'Missing draft fixture control'
    bounds = list(map(int, re.findall(r'\d+', matches[-1].get('bounds'))))
    text('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))

def launch():
    text('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c',
         'android.intent.category.LAUNCHER', '-f', '0x10200000', '-n', PACKAGE+'/.MainActivity')

def editor_text():
    return next((n.get('text') for n in nodes().iter('node') if n.get('class') == 'android.widget.EditText'), None)

assert text('get-serialno').startswith('emulator-')
assert text('shell', 'getprop', 'ro.kernel.qemu') == '1'
assert text('shell', 'pm', 'clear', PACKAGE) == 'Success'  # Disposable fixture state only.
text('shell', 'wm', 'size', '360x800')
text('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
launch()
tap(lambda n: n.get('content-desc') in ('Open navigation', 'Expand navigation'))
tap(lambda n: n.get('text') == 'Add record')
key = hashlib.sha256(b'create:0').hexdigest()
path = 'files/workspace-drafts-v1/' + key
before_bytes = adb('exec-out', 'run-as', PACKAGE, 'cat', path, check=False).stdout
tap(lambda n: n.get('class') == 'android.widget.EditText')
text('shell', 'input', 'keyevent', '123')
# Plain synthetic text avoids shell metacharacters; no credentials or server submission.
marker = 'DRAFTPROCESS123' + 'x' * 5000
text('shell', 'input', 'text', marker)
text('shell', 'input', 'keyevent', '111')
expected = editor_text()
assert expected and marker in expected and len(expected) > 4096
# Wait for the acknowledged atomic rename, not merely for an elapsed debounce interval.
deadline = time.monotonic() + 20
while True:
    committed = adb('exec-out', 'run-as', PACKAGE, 'cat', path, check=False)
    temporary = adb('shell', 'run-as', PACKAGE, 'test', '-e', path+'.new', check=False)
    if committed.returncode == 0 and committed.stdout != before_bytes and temporary.returncode != 0:
        break
    assert time.monotonic() < deadline, 'No acknowledged encrypted replacement'
    time.sleep(.1)
pid_before = text('shell', 'pidof', PACKAGE)
text('shell', 'input', 'keyevent', '3')
time.sleep(1)
text('shell', 'am', 'kill', PACKAGE)
deadline = time.monotonic() + 10
while text('shell', 'pidof', PACKAGE, check=False):
    assert time.monotonic() < deadline, 'Background process did not die'
    time.sleep(.1)
launch()
deadline = time.monotonic() + 15
while editor_text() != expected:
    assert time.monotonic() < deadline, 'Acknowledged large draft was not restored exactly'
    time.sleep(.1)
pid_after = text('shell', 'pidof', PACKAGE)
assert pid_after and pid_after != pid_before
(OUT/'draft-process-death.txt').write_text(
    f'Acknowledged encrypted draft restored exactly after external process death: {len(expected)} chars; PID {pid_before} -> {pid_after}. No submission.\n')
print('Acknowledged large draft external process-death check passed')
