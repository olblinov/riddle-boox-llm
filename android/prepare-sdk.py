#!/usr/bin/env python3
"""Fetch checksum-pinned SDK artifacts; extract only runtime code and native libraries."""
import hashlib, json, pathlib, subprocess, zipfile, shutil
root = pathlib.Path(__file__).resolve().parent.parent
cache = root / 'work/onyx-sdk/artifacts'
out = root / 'android/build-sdk'
cache.mkdir(parents=True, exist_ok=True)
items = json.loads((root / 'android/sdk-lock.json').read_text())
for item in items:
    artifact = cache / item['file']
    if not artifact.exists():
        subprocess.run(['curl', '-fsSL', item['url'], '-o', str(artifact)], check=True)
    if hashlib.sha256(artifact.read_bytes()).hexdigest() != item['sha256']:
        raise SystemExit('SDK checksum mismatch: ' + artifact.name)
# Verify every input before replacing generated output; stale JARs/SOs never enter a build.
if out.exists(): shutil.rmtree(out)
out.mkdir(parents=True)
for item in items:
    artifact = cache / item['file']
    if artifact.suffix == '.jar':
        shutil.copyfile(artifact, out / artifact.name)
    else:
        with zipfile.ZipFile(artifact) as archive:
            (out / (artifact.stem + '.jar')).write_bytes(archive.read('classes.jar'))
            for name in archive.namelist():
                if name.startswith(('jni/arm64-v8a/', 'jni/armeabi-v7a/')) and name.endswith('.so'):
                    target = out / name.replace('jni/', 'lib/', 1)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    # First entry in sdk-lock.json wins; native-libraries.json records exact chosen bytes.
                    if not target.exists(): target.write_bytes(archive.read(name))

expected = json.loads((root / 'android/native-libraries.json').read_text())
for name, info in expected.items():
    library = out / name.replace('jni/', 'lib/', 1)
    if hashlib.sha256(library.read_bytes()).hexdigest() != info['sha256']:
        raise SystemExit('Native library checksum mismatch: ' + name)
