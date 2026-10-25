"""Puts the speech-recognition model of voice messages on the server.

    python infra/publish-stt-model.py <folder with encoder.int8.onnx, decoder.onnx, joiner.int8.onnx, tokens.txt>

The names, sizes and SHA-256 are the ones pinned in core/src/stt.rs: the script refuses files that do
not match them, because the apps would refuse them too. The server serves the files from
/var/lib/neochat/models as /v1/app/models/stt-<model id>-<file>; the host is the one reachable as
`ssh neochat` (NEOCHAT_HOST). Safe to run again: existing files are replaced.
"""
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOST = os.environ.get('NEOCHAT_HOST', 'neochat')


def pinned():
    source = (ROOT / 'core' / 'src' / 'stt.rs').read_text(encoding='utf-8')
    model_id = re.search(r'const MODEL_ID: &str = "([^"]+)";', source).group(1)
    files = [(name, int(size.replace('_', '')), digest)
             for name, size, digest in re.findall(r'\("([\w.]+)", ([\d_]+), "([0-9a-f]{64})"\)', source)]
    if not files:
        sys.exit('не нашёл файлы модели в core/src/stt.rs')
    return model_id, files


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    folder = Path(sys.argv[1])
    model_id, files = pinned()
    paths = []
    for name, size, digest in files:
        p = folder / name
        if not p.is_file():
            sys.exit(f'нет файла {p}')
        data = p.read_bytes()
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            sys.exit(f'{name}: размер или SHA-256 не совпали с core/src/stt.rs')
        paths.append((p, f'stt-{model_id}-{name}', digest))
    subprocess.run(['ssh', HOST, 'rm -rf /tmp/neochat-models && mkdir /tmp/neochat-models'], check=True)
    for p, remote_name, _ in paths:
        # Relative to the folder: scp reads a drive letter ("D:") as a host name.
        subprocess.run(['scp', '-q', p.name, f'{HOST}:/tmp/neochat-models/{remote_name}'], cwd=folder, check=True)
    sums = ''.join(f'{digest}  /var/lib/neochat/models/{remote_name}\n' for _, remote_name, digest in paths)
    remote = '''set -euo pipefail
install -d -m 755 -o neochat -g neochat /var/lib/neochat/models
for f in /tmp/neochat-models/stt-*; do install -m 644 -o neochat -g neochat "$f" /var/lib/neochat/models/; done
rm -rf /tmp/neochat-models
sha256sum -c --quiet'''
    subprocess.run(['ssh', HOST, 'sudo', 'bash', '-c', f"'{remote}'"], input=sums.encode(), check=True)
    print(f'модель {model_id}: {len(paths)} файла на сервере, суммы проверены')


if __name__ == '__main__':
    main()
