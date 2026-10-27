#!/usr/bin/env python3
"""Run release DEX only on an explicitly named owned emulator, no app install/data access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--avd', required=True)
parser.add_argument('--api', type=int, required=True)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--evidence', type=Path, required=True)
parser.add_argument('--log', required=True)
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'Physical devices are never permitted'
assert args.avd in ['xmouse_unified_settings_api35', 'xmouse_control_performance_api26']
root = Path(__file__).resolve().parents[3]
sdk = Path(os.environ['ANDROID_HOME'])
assert str(sdk) == os.environ['ANDROID_SDK_ROOT']
env = os.environ.copy()
env.pop('ADB_SERVER_SOCKET', None)
adb = [str(sdk / 'platform-tools/adb'), '-P', '5049', '-s', args.serial]


def device(*command):
    return subprocess.check_output(adb + list(command), env=env, text=True, stderr=subprocess.STDOUT)


identity = dict(serial=args.serial, avd=device('emu', 'avd', 'name').splitlines()[0],
                api=int(device('shell', 'getprop', 'ro.build.version.sdk').strip()),
                fingerprint=device('shell', 'getprop', 'ro.build.fingerprint').strip())
assert identity['avd'] == args.avd and identity['api'] == args.api
assert device('shell', 'getprop', 'sys.boot_completed').strip() == '1'
evidence = args.evidence.resolve()
audit = json.loads((evidence / 'release-audit/audit.json').read_text())
assert hashlib.sha256(args.apk.read_bytes()).hexdigest() == audit['apk_sha256']
(evidence / (args.log + '-identity.json')).write_text(json.dumps(identity, indent=2) + '\n')
classes = evidence / 'probe-classes'
classes.mkdir(exist_ok=True)
fixtures = evidence / 'fixtures'
fixtures.mkdir(exist_ok=True)
for source in (root / 'app/src/androidTest/assets/ssh-crypto').glob('*.pem'):
    shutil.copy2(source, fixtures / source.name)
shutil.copy2(root / 'app/src/test/resources/legacy/layouts/xmousesep/keyLayoutFile.csv', fixtures)
for algorithm in ['ED25519', 'ED448']:
    target = fixtures / (algorithm.lower() + '.pem')
    if not target.exists():
        subprocess.run(['openssl', 'genpkey', '-algorithm', algorithm, '-out', str(target)], check=True)
target = fixtures / 'rsa-pkcs8-encrypted.pem'
if not target.exists():
    subprocess.run(['openssl', 'pkcs8', '-topk8', '-in', str(fixtures / 'rsa1024.pem'), '-v2',
        'aes-256-cbc', '-iter', '2048', '-passout', 'pass:NOT_REAL_XMOUSE_FIXTURE', '-out', str(target)], check=True)
subprocess.run(['javac', '--release', '17', '-cp', str(sdk / 'platforms/android-37.0/android.jar'),
    '-d', str(classes), str(Path(__file__).with_name('ReleaseDexProbe.java'))], check=True)
probe = evidence / 'probe.jar'
subprocess.run([str(sdk / 'build-tools/36.0.0/d8'), '--min-api', '26', '--lib',
    str(sdk / 'platforms/android-37.0/android.jar'), '--output', str(probe),
    *[str(path) for path in classes.glob('*.class')]], check=True)
remote = '/data/local/tmp/xmouse-control-performance'
device('shell', 'mkdir', '-p', remote)
for source in [args.apk, probe, evidence / 'release-audit/runtime-map.json', fixtures]:
    device('push', str(source), remote + '/')
remote_apk = remote + '/' + args.apk.name
device_hash = device('shell', 'sha256sum', remote_apk).split()[0]
assert device_hash == audit['apk_sha256'], 'Probe must execute the audited signed artifact'
command = (f'CLASSPATH={shlex.quote(remote_apk + ":" + remote + "/probe.jar")} app_process /system/bin ReleaseDexProbe '
           f'{remote}/runtime-map.json {remote}/fixtures')
result = subprocess.run(adb + ['shell', command], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
(evidence / (args.log + '.log')).write_text(result.stdout)
print('\n'.join(line for line in result.stdout.splitlines() if not line.startswith('CONSTRUCT ')))
assert result.returncode == 0, f'Probe exited {result.returncode}'
assert 'RELEASE_DEX_PROBE_OK' in result.stdout
