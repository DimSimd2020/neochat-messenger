"""Publishes dist/NeoChat-<version>-* as the current app update.

    python infra/publish-update.py 0.4.0 "Что нового"

The manifest is signed with the offline Ed25519 key (NEOCHAT_UPDATE_KEY, default
E:/dev/keys/update-signing.key); the core accepts only manifests signed by it.
Files go to /var/lib/neochat/updates on the host reachable as `ssh neochat`.
"""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

ROOT = Path(__file__).resolve().parent.parent
HOST = os.environ.get('NEOCHAT_HOST', 'neochat')
FILES = {'windows': '-x64.msi', 'android': '-android.apk', 'ios': '-ios-unsigned.ipa'}


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    version, notes = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else '')
    key = Ed25519PrivateKey.from_private_bytes(Path(os.environ.get('NEOCHAT_UPDATE_KEY', 'E:/dev/keys/update-signing.key')).read_bytes())
    manifest = {'notes': notes, 'platforms': {}}
    paths = []
    for platform, suffix in FILES.items():
        p = ROOT / 'dist' / f'NeoChat-{version}{suffix}'
        if not p.exists():
            print(f'нет {p.name}, {platform} пропущен')
            continue
        data = p.read_bytes()
        manifest['platforms'][platform] = {'version': version, 'file': p.name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        paths.append(p)
    if not paths:
        sys.exit('нечего публиковать')
    body = json.dumps(manifest, ensure_ascii=False, sort_keys=True).encode()
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / 'manifest.json').write_bytes(body)
        (Path(tmp) / 'manifest.sig').write_bytes(key.sign(body))
        subprocess.run(['ssh', HOST, 'rm -rf /tmp/neochat-update && mkdir /tmp/neochat-update'], check=True)
        subprocess.run(['scp', '-q', *map(str, paths), str(Path(tmp) / 'manifest.json'), str(Path(tmp) / 'manifest.sig'), f'{HOST}:/tmp/neochat-update/'], check=True)
    # Files first, the manifest last: clients never see a manifest for files not yet in place.
    remote = '''set -e
D=/var/lib/neochat/updates
install -d -m 755 -o neochat -g neochat $D
for f in /tmp/neochat-update/NeoChat-*; do install -m 644 -o neochat -g neochat "$f" $D/; done
install -m 644 -o neochat -g neochat /tmp/neochat-update/manifest.sig $D/manifest.sig.new
install -m 644 -o neochat -g neochat /tmp/neochat-update/manifest.json $D/manifest.json.new
mv $D/manifest.sig.new $D/manifest.sig && mv $D/manifest.json.new $D/manifest.json
cd $D && ls NeoChat-* | grep -v -F "$1" | xargs -r rm -f
rm -rf /tmp/neochat-update'''
    subprocess.run(['ssh', HOST, 'sudo', 'bash', '-s', '--', f'NeoChat-{version}-'], input=remote.encode(), check=True)
    print(f'опубликовано: {version} ({", ".join(manifest["platforms"])})')


if __name__ == '__main__':
    main()
