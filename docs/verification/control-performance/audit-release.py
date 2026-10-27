#!/usr/bin/env python3
"""Audit actual optimized APK definitions and emit inputs for ReleaseDexProbe.

Uses only the APK, public R8 output and repository sources, never signing inputs.
Run after assembleRelease; see ../control-performance.md for the runtime command.
"""
import argparse
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
from zipfile import ZipFile


def dex_definitions(data):
    """Read class_data definitions, not mere strings/references to absent classes."""
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]

    def leb(offset):
        value = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7

    strings = []
    for index in range(u32(56)):
        offset = u32(u32(60) + index * 4)
        _, offset = leb(offset)
        strings.append(data[offset:data.index(0, offset)].decode('utf-8', errors='replace'))
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    fields = []
    for index in range(u32(80)):
        owner, kind, name = struct.unpack_from('<HHI', data, u32(84) + index * 8)
        fields.append((types[owner], types[kind], strings[name]))
    methods = []
    for index in range(u32(88)):
        owner, proto, name = struct.unpack_from('<HHI', data, u32(92) + index * 8)
        methods.append((strings[name], u32(u32(76) + proto * 12 + 8) == 0))
    classes = {}
    for index in range(u32(96)):
        offset = u32(100) + index * 32
        name = types[u32(offset)][1:-1].replace('/', '.')
        cursor = u32(offset + 24)
        defined = []
        constructor = False
        defined_methods = []
        if cursor:
            counts = []
            for _ in range(4):
                count, cursor = leb(cursor)
                counts.append(count)
            for count in counts[:2]:
                field_index = 0
                for _ in range(count):
                    delta, cursor = leb(cursor)
                    flags, cursor = leb(cursor)
                    field_index += delta
                    defined.append((fields[field_index][2], fields[field_index][1], flags))
            for count in counts[2:]:
                method_index = 0
                for _ in range(count):
                    delta, cursor = leb(cursor)
                    _, cursor = leb(cursor)
                    _, cursor = leb(cursor)
                    method_index += delta
                    method, no_parameters = methods[method_index]
                    defined_methods.append(method)
                    constructor |= method == '<init>' and no_parameters
        classes[name] = dict(fields=defined, methods=defined_methods, no_arg_constructor=constructor)
    return classes


def mapping(path):
    classes, members = {}, defaultdict(dict)
    owner = None
    for line in path.read_text().splitlines():
        match = re.fullmatch(r'(\S+) -> (\S+):', line)
        if match:
            owner, renamed = match.groups()
            classes[owner] = renamed
        elif owner and line.startswith('    ') and ' -> ' in line:
            original, renamed = line.strip().split(' -> ')
            original = re.sub(r'^\d+:\d+:', '', original)
            original = re.sub(r':\d+(?::\d+)?$', '', original)
            # Ignore inlined frames from a different owner, not real entry points.
            name = original.split(' ', 1)[-1].split('(')[0]
            if '.' not in name:
                members[owner][original] = renamed
    return classes, members


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    sdk = Path(os.environ['ANDROID_HOME'])
    assert str(sdk) == os.environ['ANDROID_SDK_ROOT']
    args.output.mkdir(parents=True, exist_ok=True)
    classes, members = mapping(args.mapping / 'mapping.txt')
    with ZipFile(args.apk) as apk:
        assert apk.testzip() is None, 'APK CRC failure'
        names = apk.namelist()
        definitions = {}
        dex_names = [n for n in names if re.fullmatch(r'classes\d*\.dex', n)]
        for name in dex_names:
            definitions.update(dex_definitions(apk.read(name)))
        forbidden = [n for n in names if re.search(
            r'\.(?:html?|[mc]?js|apk|jks|keystore|pem|p12|pfx|ttf|otf)$|(?:fixture|browser.reference|gradle.properties)',
            n, re.I)]
        assert not forbidden, forbidden
        assert all(n in ['assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm']
                   for n in names if n.startswith('assets/') and not n.endswith('/'))
        banned = ('androidx.test.', 'org.junit.', 'org.mockito.', 'org.apache.sshd.',
                  'com.stripe1.xmouse.MainInsetsFixtureActivity', 'org.xbill.DNS.spi.')
        assert not [n for n in definitions if n.startswith(banned)]
        assert not [n for n, mapped in classes.items() if n.startswith(banned) and mapped in definitions]
        for source_root in ['debug', 'test', 'androidTest', 'testFixtures']:
            for path in (root / 'app/src' / source_root).rglob('*'):
                if path.suffix not in ['.kt', '.java']:
                    continue
                source = path.read_text()
                package = re.search(r'^package ([\w.]+)', source, re.M)
                if not package:
                    continue
                prefix = package[1] + '.' + path.stem
                assert not [n for n, mapped in classes.items()
                            if (n == prefix or n.startswith(prefix + '$')) and mapped in definitions], prefix
        services = {}
        for name in names:
            if name.startswith('META-INF/services/') and not name.endswith('/'):
                entries = [line.split('#')[0].strip() for line in apk.read(name).decode().splitlines()]
                services[name] = [entry for entry in entries if entry]
                assert all(entry in definitions for entry in services[name]), (name, services[name])
        assert not any('InetAddressResolverProvider' in n or 'NameServiceDescriptor' in n for n in services)
    # Every reflection-loaded implementation supplied in the app's JSch JAR must
    # remain named/constructible, except optional GSSAPI (never selected by backend).
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1/com.github.mwiede/jsch/2.28.8'
    jar = next(cache.glob('*/*.jar'))
    bytecode = subprocess.check_output(['javap', '-c', '-p', '-classpath', str(jar), 'com.jcraft.jsch.JSch'], text=True)
    algorithms = sorted(set(re.findall(r'// String (com.jcraft.jsch.[^\n]+)', bytecode)))
    algorithms = [n for n in algorithms if n not in [
        'com.jcraft.jsch.UserAuthGSSAPIWithMIC', 'com.jcraft.jsch.jgss.GSSContextKrb5']]
    assert all(n in definitions for n in algorithms), [n for n in algorithms if n not in definitions]
    assert all(definitions[n]['no_arg_constructor'] for n in algorithms), [
        n for n in algorithms if not definitions[n]['no_arg_constructor']]
    for name in ['JSch', 'Session', 'KeyPair']:
        assert classes['com.jcraft.jsch.' + name].startswith('com.jcraft.jsch.'), 'Package-private reflection access'
    for method in ['xMouseClickMouse', 'xMouseKeyboardSend']:
        assert method in definitions['com.stripe1.xmouse.MainActivity']['methods'], 'XML onClick: ' + method
    constants = {}
    for name, expression in re.findall(r'public static int (KEY_\w+) = ([^;]+);',
            (root / 'app/src/main/java/com/stripe1/xmouse/InputEventCodes.java').read_text()):
        # Generated declarations have only integer literals, aliases and KEY_MAX+1.
        if expression.strip() in constants:
            constants[name] = constants[expression.strip()]
        elif expression.replace(' ', '') == '(KEY_MAX+1)':
            constants[name] = constants['KEY_MAX'] + 1
        else:
            constants[name] = int(expression, 0)
    defined_fields = definitions[classes['com.stripe1.xmouse.InputEventCodes']]['fields']
    assert {n for n, kind, flags in defined_fields if kind == 'I' and flags & 9 == 9} == set(constants)
    tools = sdk / 'build-tools/36.0.0'
    def run(name, *command):
        result = subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)
        (args.output / name).write_text(result)
        return result
    cert = run('signature.txt', str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk))
    fingerprint = 'c1c5faca7bfd24ce2bf5467a731b71b0218bcb503eb20f814d370925a4b88eed'
    assert f'Signer #1 certificate SHA-256 digest: {fingerprint}' in cert
    assert 'Number of signers: 1' in cert and 'Verified using v2 scheme (APK Signature Scheme v2): true' in cert
    badging = run('badging.txt', str(tools / 'aapt2'), 'dump', 'badging', str(args.apk))
    for expected in ["name='com.stripe1.xmouse'", "versionCode='26'", "versionName='2.1.0'",
                     "minSdkVersion:'26'", "targetSdkVersion:'37'", "compileSdkVersion='37'",
                     "application-label:'XMouse-NG'", "launchable-activity: name='com.stripe1.xmouse.MainActivity'"]:
        assert expected in badging, expected
    manifest = run('manifest.txt', str(tools / 'aapt2'), 'dump', 'xmltree', str(args.apk), '--file', 'AndroidManifest.xml')
    assert not re.search(r'E: instrumentation|android:(?:debuggable|testOnly).*0xffffffff', manifest)
    run('alignment.txt', str(tools / 'zipalign'), '-c', '-P', '16', '-v', '4', str(args.apk))
    aapt = (root / 'app/build/intermediates/aapt_proguard_file/release/processReleaseResources/aapt_rules.txt').read_text()
    for name in re.findall(r'-keep class (com\.stripe1\.xmouse\.\S+) \{', aapt):
        assert name in definitions, name
    config = (args.mapping / 'configuration.txt').read_text()
    assert '-ignorewarnings' not in config and not re.search(r'^-dontwarn\s+\*', config, re.M)
    assert '-dontshrink' not in config and '-dontobfuscate' not in config and '-dontoptimize' not in config
    assert classes['com.stripe1.xmouse.YDoTool'] != 'com.stripe1.xmouse.YDoTool'
    selected = {name for name in classes if name.startswith(('com.stripe1.xmouse.', 'com.jcraft.jsch.',
                'org.bouncycastle.jce.provider.', 'org.xbill.DNS.'))}
    runtime = dict(classes={n: classes[n] for n in selected}, members={n: members[n] for n in selected},
                   constants=constants, algorithms=algorithms)
    (args.output / 'runtime-map.json').write_text(json.dumps(runtime, indent=2) + '\n')
    report = dict(apk=str(args.apk.resolve()), apk_bytes=args.apk.stat().st_size,
        apk_sha256=hashlib.sha256(args.apk.read_bytes()).hexdigest(), dex_files=len(dex_names),
        class_definitions=len(definitions), reflection_constants=len(constants),
        jsch_implementations=len(algorithms), zip_entries=len(names), services=services,
        mapping_sha256=hashlib.sha256((args.mapping / 'mapping.txt').read_bytes()).hexdigest(),
        certificate_sha256=fingerprint, tests_or_fixtures_found=0)
    (args.output / 'audit.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
