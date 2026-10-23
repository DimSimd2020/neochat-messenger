"""Publishes dist/NeoChat-<version>-* as an app update.

    python infra/publish-update.py 0.6.7.2 "Что нового" beta
    python infra/publish-update.py 0.6.8 "Что нового" stable
    python infra/publish-update.py 0.6.9.5 "Диагностика звонка" alpha

The third argument is the channel (stable by default). stable replaces `platforms`,
which is what every client without a channel still reads. beta only fills
`channels.beta` and leaves the stable entries alone.

alpha takes dist/NeoChat-<version>-alpha-{x64.msi,android.apk,ios-unsigned.ipa}, tags the
entries "alpha" and writes them to updates/alpha/ with its own signed manifest. The server
gives that directory only to accounts in the alpha_testers table (migration 0013); the
public manifest and files are not touched. Builds for it are not released from git.

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


def parts(version):
    out = []
    for piece in str(version).split('.'):
        out.append(int(piece) if piece.isdigit() else 0)
    return out


def newer(candidate, current):
    a, b = parts(candidate), parts(current)
    for i in range(max(len(a), len(b))):
        x = a[i] if i < len(a) else 0
        y = b[i] if i < len(b) else 0
        if x != y:
            return x > y
    return False


def current_manifest():
    r = subprocess.run(['ssh', HOST, 'sudo', 'cat', '/var/lib/neochat/updates/manifest.json'], capture_output=True)
    if r.returncode != 0 or not r.stdout.strip():
        return None
    return json.loads(r.stdout)


def entries(version, tag=''):
    built = {}
    paths = []
    for platform, suffix in FILES.items():
        p = ROOT / 'dist' / f'NeoChat-{version}{"-" + tag if tag else ""}{suffix}'
        if not p.exists():
            print(f'нет {p.name}, {platform} пропущен')
            continue
        data = p.read_bytes()
        built[platform] = {'version': version, 'file': p.name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        if tag:
            built[platform]['tag'] = tag
        paths.append(p)
    return built, paths


def referenced(manifest):
    names = []
    for section in (manifest.get('platforms') or {}, (manifest.get('channels') or {}).get('stable') or {}, (manifest.get('channels') or {}).get('beta') or {}):
        for entry in section.values():
            name = entry.get('file')
            if name and name not in names:
                names.append(name)
    return names


def publish_alpha(version, notes, key):
    built, paths = entries(version, 'alpha')
    if not paths:
        sys.exit('нечего публиковать')
    r = subprocess.run(['ssh', HOST, 'sudo', 'cat', '/var/lib/neochat/updates/alpha/manifest.json'], capture_output=True)
    current = json.loads(r.stdout) if r.returncode == 0 and r.stdout.strip() else {}
    alpha = dict((current.get('channels') or {}).get('alpha') or {})
    alpha.update(built)
    manifest = {'notes': notes, 'channels': {'alpha': alpha}}
    body = json.dumps(manifest, ensure_ascii=False, sort_keys=True).encode()
    keep = [e['file'] for e in alpha.values()]
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / 'manifest.json').write_bytes(body)
        (Path(tmp) / 'manifest.sig').write_bytes(key.sign(body))
        subprocess.run(['ssh', HOST, 'rm -rf /tmp/neochat-update && mkdir /tmp/neochat-update'], check=True)
        subprocess.run(['scp', '-q', *map(str, paths), str(Path(tmp) / 'manifest.json'), str(Path(tmp) / 'manifest.sig'), f'{HOST}:/tmp/neochat-update/'], check=True)
    # Same order as the public channel: files first, the manifest last.
    remote = '''set -euo pipefail
D=/var/lib/neochat/updates
backup=$1
shift
install -d -m 755 /var/backups/neochat-releases
tar -czf "/var/backups/neochat-releases/$backup" -C /var/lib/neochat updates
install -d -m 755 -o neochat -g neochat "$D" "$D/alpha"
shopt -s nullglob
for f in /tmp/neochat-update/NeoChat-*; do install -m 644 -o neochat -g neochat "$f" "$D/alpha/"; done
install -m 644 -o neochat -g neochat /tmp/neochat-update/manifest.sig "$D/alpha/manifest.sig.new"
install -m 644 -o neochat -g neochat /tmp/neochat-update/manifest.json "$D/alpha/manifest.json.new"
mv "$D/alpha/manifest.sig.new" "$D/alpha/manifest.sig"
mv "$D/alpha/manifest.json.new" "$D/alpha/manifest.json"
cd "$D/alpha"
for f in NeoChat-*; do
  keep=0
  for k in "$@"; do [ "$f" = "$k" ] && keep=1; done
  [ "$keep" = 1 ] || rm -f "$f"
done
rm -rf /tmp/neochat-update'''
    subprocess.run(['ssh', HOST, 'sudo', 'bash', '-s', '--', f'updates-before-{version}-alpha.tar.gz', *keep], input=remote.encode(), check=True)
    print(f'опубликовано: {version} канал alpha (только для alpha_testers)')
    print('alpha: ' + ', '.join(f'{k} {v["version"]}' for k, v in sorted(alpha.items())))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    version = sys.argv[1]
    notes = sys.argv[2] if len(sys.argv) > 2 else ''
    channel = sys.argv[3] if len(sys.argv) > 3 else 'stable'
    if channel not in ('stable', 'beta', 'alpha'):
        sys.exit('канал: stable, beta или alpha')
    key = Ed25519PrivateKey.from_private_bytes(Path(os.environ.get('NEOCHAT_UPDATE_KEY', 'E:/dev/keys/update-signing.key')).read_bytes())
    if channel == 'alpha':
        return publish_alpha(version, notes, key)
    built, paths = entries(version)
    if not paths:
        sys.exit('нечего публиковать')
    current = current_manifest()
    if channel == 'beta' and not current:
        sys.exit('нет стабильного манифеста, beta не публикую')
    platforms = dict((current or {}).get('platforms') or {})
    channels = (current or {}).get('channels') or {}
    stable = dict(channels.get('stable') or platforms)
    beta = dict(channels.get('beta') or {})
    if channel == 'beta':
        beta.update(built)
        kept_notes = (current or {}).get('notes', '')
    else:
        platforms = built
        stable = built
        for platform, entry in built.items():
            old = beta.get(platform) or {}
            if newer(entry['version'], old.get('version', '0')):
                beta[platform] = entry
        kept_notes = notes
    manifest = {'notes': kept_notes, 'platforms': platforms, 'channels': {'stable': stable, 'beta': beta}}
    if channel == 'beta':
        for platform, entry in (current.get('platforms') or {}).items():
            got = manifest['platforms'].get(platform) or {}
            if got.get('version') != entry.get('version') or got.get('sha256') != entry.get('sha256'):
                sys.exit(f'beta-публикация изменила бы stable {platform}')
    body = json.dumps(manifest, ensure_ascii=False, sort_keys=True).encode()
    keep = referenced(manifest)
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / 'manifest.json').write_bytes(body)
        (Path(tmp) / 'manifest.sig').write_bytes(key.sign(body))
        subprocess.run(['ssh', HOST, 'rm -rf /tmp/neochat-update && mkdir /tmp/neochat-update'], check=True)
        subprocess.run(['scp', '-q', *map(str, paths), str(Path(tmp) / 'manifest.json'), str(Path(tmp) / 'manifest.sig'), f'{HOST}:/tmp/neochat-update/'], check=True)
    # Files first, the manifest last: clients never see a manifest for files not yet in place.
    # The backup is the previous updates directory, taken before the swap.
    remote = '''set -euo pipefail
D=/var/lib/neochat/updates
backup=$1
shift
install -d -m 755 /var/backups/neochat-releases
tar -czf "/var/backups/neochat-releases/$backup" -C /var/lib/neochat updates
install -d -m 755 -o neochat -g neochat "$D"
shopt -s nullglob
for f in /tmp/neochat-update/NeoChat-*; do install -m 644 -o neochat -g neochat "$f" "$D/"; done
install -m 644 -o neochat -g neochat /tmp/neochat-update/manifest.sig "$D/manifest.sig.new"
install -m 644 -o neochat -g neochat /tmp/neochat-update/manifest.json "$D/manifest.json.new"
mv "$D/manifest.sig.new" "$D/manifest.sig"
mv "$D/manifest.json.new" "$D/manifest.json"
cd "$D"
for f in NeoChat-*; do
  keep=0
  for k in "$@"; do [ "$f" = "$k" ] && keep=1; done
  [ "$keep" = 1 ] || rm -f "$f"
done
rm -rf /tmp/neochat-update'''
    subprocess.run(['ssh', HOST, 'sudo', 'bash', '-s', '--', f'updates-before-{version}-{channel}.tar.gz', *keep], input=remote.encode(), check=True)
    stable_versions = ', '.join(f'{k} {v.get("version")}' for k, v in sorted(manifest['platforms'].items()))
    beta_versions = ', '.join(f'{k} {v.get("version")}' for k, v in sorted(manifest['channels']['beta'].items())) or 'нет'
    print(f'опубликовано: {version} канал {channel}')
    print(f'stable: {stable_versions}')
    print(f'beta: {beta_versions}')


if __name__ == '__main__':
    main()
