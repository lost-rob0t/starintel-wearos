#!/usr/bin/env python3
"""Disposable-emulator-only acknowledged encrypted draft/process-death check; never submits."""
import hashlib
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'actor.starintel.quasar'
EMULATOR_VERIFIED = False
OUT = pathlib.Path('build/draft-storage')
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, check=True):
    return subprocess.run(['adb', *args], capture_output=True, check=check)

def text(*args, check=True):
    return adb(*args, check=check).stdout.decode().strip()

def nodes():
    text('shell', 'uiautomator', 'dump', '/sdcard/draft-process.xml')
    return ET.fromstring(text('shell', 'cat', '/sdcard/draft-process.xml'))

def bounds(node):
    values = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        return None
    return values

def find_node(tree, predicate, scope=None):
    roots = [n for n in tree.iter('node') if n.get('content-desc') == scope] if scope else [tree]
    return next((n for root in roots for n in root.iter('node')
                 if predicate(n) and bounds(n) is not None), None)

def tap_node(node):
    left, top, right, bottom = bounds(node)
    text('shell', 'input', 'tap', str((left+right)//2), str((top+bottom)//2))

def tap(predicate, label='fixture control'):
    deadline = time.monotonic() + 10
    while True:
        node = find_node(nodes(), predicate)
        if node is not None:
            tap_node(node)
            return
        assert time.monotonic() < deadline, 'Missing ' + label
        time.sleep(.1)

def tap_destination(label):
    # A physical pixel size alone does not establish dp geometry. Scroll only
    # the opened drawer; never accidentally click the covered Home route card.
    deadline = time.monotonic() + 10
    while True:
        tree = nodes()
        drawer = find_node(tree, lambda n: n.get('content-desc') == 'Workspace destinations')
        if drawer is not None:
            break
        assert time.monotonic() < deadline, 'Navigation drawer did not open'
        time.sleep(.1)
    for attempt in range(9):
        ET.ElementTree(tree).write(OUT/f'drawer-{attempt}.xml')
        target = find_node(tree, lambda n: n.get('text') == label, 'Workspace destinations')
        if target is not None:
            tap_node(target)
            return
        if attempt == 8:
            raise AssertionError('Missing drawer destination after bounded scroll: ' + label)
        drawer = find_node(tree, lambda n: n.get('content-desc') == 'Workspace destinations')
        assert drawer is not None, 'Drawer disappeared during destination lookup'
        left, top, right, bottom = bounds(drawer)
        x = str((left+right)//2)
        text('shell', 'input', 'swipe', x, str(top+(bottom-top)*4//5), x, str(top+(bottom-top)//5), '250')
        tree = nodes()

def preserve_failure():
    if not EMULATOR_VERIFIED:
        return
    for name, args in [('crashes.log', ('logcat', '-d', '-b', 'crash')),
                       ('window.txt', ('shell', 'dumpsys', 'window')),
                       ('display.txt', ('shell', 'wm', 'density'))]:
        result = adb(*args, check=False)
        (OUT/name).write_bytes(result.stdout + result.stderr)
    try:
        ET.ElementTree(nodes()).write(OUT/'failure.xml')
    except Exception as error:
        (OUT/'hierarchy-error.txt').write_text(str(error))

def launch():
    text('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c',
         'android.intent.category.LAUNCHER', '-f', '0x10200000', '-n', PACKAGE+'/.MainActivity')

def editor_text():
    return next((n.get('text') for n in nodes().iter('node') if n.get('class') == 'android.widget.EditText'), None)

def main():
    global EMULATOR_VERIFIED
    assert text('get-serialno').startswith('emulator-')
    assert text('shell', 'getprop', 'ro.kernel.qemu') == '1'
    EMULATOR_VERIFIED = True
    assert text('shell', 'pm', 'clear', PACKAGE) == 'Success'  # Disposable fixture state only.
    text('shell', 'wm', 'size', '360x800')
    text('shell', 'wm', 'density', '160')
    (OUT/'display-before.txt').write_text(text('shell', 'wm', 'size') + '\n' + text('shell', 'wm', 'density') + '\n')
    text('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    launch()
    tap(lambda n: n.get('content-desc') in ('Open navigation', 'Expand navigation'))
    tap_destination('Add record')
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

if __name__ == '__main__':
    try:
        main()
    except Exception:
        preserve_failure()
        raise
