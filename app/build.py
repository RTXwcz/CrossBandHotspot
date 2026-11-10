# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the Android APK using SDK tools, JDK 17+ and Python 3.10+."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

from fetch_deps import fetch_all

ROOT = Path(__file__).resolve().parent


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.getenv('ANDROID_HOME') or os.getenv('ANDROID_SDK_ROOT'))
    parser.add_argument('--platform', default='android-37.0')
    parser.add_argument('--build-tools', default='36.0.0')
    parser.add_argument('--keystore', type=Path, default=Path.home() / '.android/debug.keystore')
    parser.add_argument('--alias', default='androiddebugkey')
    parser.add_argument('--store-pass-env', default='CROSSBAND_STORE_PASSWORD')
    parser.add_argument('--key-pass-env', default='CROSSBAND_KEY_PASSWORD')
    args = parser.parse_args()
    if args.sdk is None:
        parser.error('Set ANDROID_HOME or ANDROID_SDK_ROOT, or provide --sdk.')
    sdk = args.sdk.resolve()
    android = sdk / 'platforms' / args.platform / 'android.jar'
    tools = sdk / 'build-tools' / args.build_tools
    windows = os.name == 'nt'

    def executable(name, batch=False):
        return tools / (name + ('.bat' if batch else '.exe') if windows else name)

    fetch_all()
    output = ROOT / 'build'
    output.mkdir(exist_ok=True)
    key_store = args.keystore.expanduser().resolve()
    if key_store == (Path.home() / '.android/debug.keystore').resolve() and not key_store.exists():
        key_store.parent.mkdir(exist_ok=True)
        run('keytool', '-genkeypair', '-keystore', key_store, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US')
    store_password = 'env:' + args.store_pass_env if args.store_pass_env in os.environ else 'pass:android'
    key_password = 'env:' + args.key_pass_env if args.key_pass_env in os.environ else store_password

    with tempfile.TemporaryDirectory(prefix='compile-', dir=output) as temp:
        work = Path(temp).resolve()
        # Verify the build scratch path before TemporaryDirectory's recursive cleanup.
        assert work.is_relative_to(output.resolve())
        for directory in ['classes', 'dex', 'jars', 'native/lib/arm64-v8a']:
            (work / directory).mkdir(parents=True, exist_ok=True)
        jars = [ROOT / 'deps/bcprov.jar', ROOT / 'deps/annotation.jar']
        for name in ['libadb', 'spake2', 'sunsecurity', 'conscrypt']:
            with zipfile.ZipFile(ROOT / f'deps/{name}.aar') as archive:
                jar = work / f'jars/{name}.jar'
                jar.write_bytes(archive.read('classes.jar'))
                jars.append(jar)
                for entry in archive.namelist():
                    if entry.startswith('jni/arm64-v8a/') and entry.endswith('.so'):
                        (work / 'native/lib/arm64-v8a' / Path(entry).name).write_bytes(archive.read(entry))
        run(executable('aapt2'), 'link', '-I', android, '--manifest', ROOT / 'AndroidManifest.xml', '-o', work / 'unsigned.apk')
        run('javac', '--release', '17', '-encoding', 'UTF-8',
            '-classpath', os.pathsep.join(map(str, [android, *jars])),
            '-d', work / 'classes', *sorted(ROOT.glob('src/**/*.java')))
        run(executable('d8', batch=True), '--lib', android, '--min-api', '36',
            '--output', work / 'dex', *sorted(work.glob('classes/**/*.class')), *jars)
        with zipfile.ZipFile(work / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
            for dex in sorted((work / 'dex').glob('*.dex')):
                apk.write(dex, dex.name)
            for native in sorted((work / 'native').glob('lib/**/*.so')):
                apk.write(native, native.relative_to(work / 'native').as_posix())
        run(executable('zipalign'), '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
        destination = output / 'CrossBandHotspot.apk'
        run(executable('apksigner', batch=True), 'sign', '--ks', key_store,
            '--ks-key-alias', args.alias, '--ks-pass', store_password, '--key-pass', key_password,
            '--out', destination, work / 'aligned.apk')
        run(executable('apksigner', batch=True), 'verify', '--verbose', destination)
    print(f'Built {destination} ({destination.stat().st_size} bytes)')


if __name__ == '__main__':
    main()
