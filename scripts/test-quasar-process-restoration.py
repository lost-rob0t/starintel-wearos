#!/usr/bin/env python3
"""Exercise real background process death, separate from ActivityScenario recreation.
Only disposable emulators. Does not change secure-window policy; XML evidence only.
"""
import pathlib, re, subprocess, time, xml.etree.ElementTree as ET
out = pathlib.Path('build/workstation-evidence'); out.mkdir(parents=True, exist_ok=True)
def adb(*args):
    result = subprocess.run(['adb', *args], text=True, capture_output=True)
    if args[:2] == ('shell', 'pidof') and result.returncode == 1:
        return ''
    result.check_returncode()
    return result.stdout.strip()
assert adb('get-serialno').startswith('emulator-')
assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/quasar-process.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/quasar-process.xml'))
def tap(predicate):
    matches = [n for n in nodes().iter('node') if predicate(n)]
    assert matches, 'Missing fixture control'
    bounds = list(map(int, re.findall(r'\d+', matches[-1].get('bounds'))))
    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
def launch():
    adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-f', '0x10200000', '-n', 'actor.starintel.quasar/.MainActivity')
adb('shell', 'wm', 'size', '360x800'); adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
launch()
tap(lambda n: n.get('text') == 'Search')
tap(lambda n: n.get('class') == 'android.widget.EditText')
adb('shell', 'input', 'keyevent', '123')
# Clear the initial wildcard, then type a distinctive query.
adb('shell', 'input', 'keyevent', '67'); adb('shell', 'input', 'text', 'process-fixture')
adb('shell', 'input', 'keyevent', '111')
before = nodes()
assert any(n.get('text') == 'process-fixture' for n in before.iter('node'))
ET.ElementTree(before).write(out/'process-before.xml')
pid_before = adb('shell', 'pidof', 'actor.starintel.quasar')
adb('shell', 'input', 'keyevent', '3')
time.sleep(1)  # Let Android save the stopped task state before killing its process.
adb('shell', 'am', 'kill', 'actor.starintel.quasar')
time.sleep(1)
assert not adb('shell', 'pidof', 'actor.starintel.quasar'), 'Background process did not die'
launch()
after = nodes()
assert any(n.get('text') == 'process-fixture' for n in after.iter('node')), 'Query/route lost after process death'
pid_after = adb('shell', 'pidof', 'actor.starintel.quasar')
assert pid_before != pid_after
ET.ElementTree(after).write(out/'process-after.xml')
(out/'process-restoration.txt').write_text(f'Background process death and task restore passed; PID {pid_before} -> {pid_after}. Query process-fixture retained. No PNG capture: production FLAG_SECURE remains enabled.\n')
