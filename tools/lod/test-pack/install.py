"""Install the pinned NeoForge 1.21.1 probe dependencies with hash verification."""
import hashlib, json, pathlib, re, shutil, urllib.request
root = pathlib.Path(__file__).resolve().parents[3]
mods = root / 'run/mods'
mods.mkdir(parents=True, exist_ok=True)
for entry in json.loads((pathlib.Path(__file__).with_name('mods.lock.json')).read_text()):
    target = mods / entry['file']
    data = target.read_bytes() if target.exists() else urllib.request.urlopen(entry['url']).read()
    if hashlib.sha512(data).hexdigest() != entry['sha512']:
        raise ValueError('Hash mismatch: ' + entry['file'])
    target.write_bytes(data)
    print(entry['file'])
config = root / 'run/config'
config.mkdir(parents=True, exist_ok=True)
shutil.copyfile(pathlib.Path(__file__).with_name('sodium-options.json'), config / 'sodium-options.json')
shutil.copyfile(pathlib.Path(__file__).with_name('voxy-config.json'), config / 'voxy-config.json')
# Fragment Render includes its world shaders; Iris disables its terrain adapter.
(config / 'fragmentrender-client.properties').write_text('lightingPreset=MEDIUM\n')
# Fragment Render bundles Modern UI; background limits distort an unattended capture.
modern_ui = config / 'ModernUI/client.toml'
modern_ui.parent.mkdir(parents=True, exist_ok=True)
text = modern_ui.read_text() if modern_ui.exists() else '[screen]\n'
for key in ('framerateInactive', 'framerateMinimized'):
    pattern = rf'(?m)^(\s*{key}\s*=\s*)\d+[^\n]*$'
    if re.search(pattern, text):
        text = re.sub(pattern, rf'\g<1>0', text)
    elif '[screen]' in text:
        text = text.replace('[screen]', f'[screen]\n{key} = 0', 1)
    else:
        text += f'\n[screen]\n{key} = 0\n'
modern_ui.write_text(text)
