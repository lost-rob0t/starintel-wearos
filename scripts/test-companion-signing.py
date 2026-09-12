#!/usr/bin/env python3
"""Exercise signer verification failures without an Android SDK."""
import os
from pathlib import Path
import subprocess
import tempfile

SCRIPT = Path(__file__).with_name("verify-companion-signing.sh").resolve()

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    phone = root / "phone.apk"
    wear = root / "wear.apk"
    phone.touch()
    wear.touch()
    signer = root / "apksigner"
    signer.write_text('''#!/usr/bin/env python3
import os, sys
apk = sys.argv[-1]
print(os.environ["PHONE_CERTS" if apk.endswith("phone.apk") else "WEAR_CERTS"])
sys.exit(int(os.environ.get("VERIFY_EXIT", "0")))
''')
    aapt = root / "aapt"
    aapt.write_text('''#!/usr/bin/env python3
import os, sys
package = os.environ.get("WEAR_PACKAGE", "actor.starintel.wear") if sys.argv[-1].endswith("wear.apk") else "actor.starintel.wear"
print("package: name='" + package + "'")
''')
    signer.chmod(0o755)
    aapt.chmod(0o755)
    digest = "a" * 64
    legacy = f"Signer #1 certificate SHA-256 digest: {digest}"
    modern = f"Signer (minSdkVersion=28, maxSdkVersion=2147483647) certificate SHA-256 digest: {digest}"
    cases = [
        ("legacy", legacy, legacy, {}, True),
        ("SDK labels", legacy, modern, {}, True),
        ("V2 label", legacy, f"V2 Signer: certificate SHA-256 digest: {digest}", {}, True),
        ("mismatch", legacy, legacy.replace(digest, "b" * 64), {}, False),
        ("missing certificate", legacy, "", {}, False),
        ("invalid signature with printed certificate", legacy, legacy, {"VERIFY_EXIT": "1"}, False),
        ("different package", legacy, legacy, {"WEAR_PACKAGE": "other.app"}, False),
        ("source stamp is not app signer", legacy, f"Source Stamp Signer certificate SHA-256 digest: {digest}", {}, False),
    ]
    for name, phone_certs, wear_certs, extra, expected in cases:
        env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"], PHONE_CERTS=phone_certs, WEAR_CERTS=wear_certs)
        env.update(extra)
        result = subprocess.run(["bash", str(SCRIPT), str(phone), str(wear)], env=env, text=True, capture_output=True)
        assert (result.returncode == 0) == expected, (name, result.stdout, result.stderr)
        print(f"PASS: {name}")

    sdk = root / "sdk"
    (sdk / "build-tools").mkdir(parents=True)
    (sdk / "build-tools" / "36.0.0").symlink_to(root, target_is_directory=True)
    env = dict(os.environ, ANDROID_HOME=str(sdk), PHONE_CERTS=legacy, WEAR_CERTS=legacy)
    result = subprocess.run(["bash", str(SCRIPT), str(phone), str(wear)], env=env, text=True, capture_output=True)
    assert result.returncode == 0, ("symlinked SDK", result.stdout, result.stderr)
    print("PASS: symlinked SDK tool discovery")
