# SPDX-License-Identifier: GPL-3.0-or-later
"""Download the exact published dependencies recorded in the lock file."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parent


def fetch(item):
    path = ROOT / 'deps' / item['file']
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256']:
        return path
    with urllib.request.urlopen(item['url'], timeout=120) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != item['sha256']:
        raise ValueError(f"SHA256 mismatch: {item['file']}")
    path.write_bytes(data)
    print(f"Downloaded {item['file']}", flush=True)
    return path


def fetch_all():
    (ROOT / 'deps').mkdir(exist_ok=True)
    items = json.loads((ROOT / 'dependencies.lock.json').read_text(encoding='utf-8'))
    with ThreadPoolExecutor(max_workers=6) as pool:
        return list(pool.map(fetch, items))


if __name__ == '__main__':
    fetch_all()
