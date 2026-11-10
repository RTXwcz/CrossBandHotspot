# SPDX-License-Identifier: GPL-3.0-or-later
"""Package the pinned corresponding dependency sources for a release."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'app/build'


def download(item):
    with urllib.request.urlopen(item['url'], timeout=120) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != item['sha256']:
        raise ValueError(f"SHA256 mismatch: {item['file']}")
    return item['file'], data


def main():
    items = json.loads((ROOT / 'third-party-sources.lock.json').read_text(encoding='utf-8'))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    destination = OUTPUT / 'CrossBandHotspot-dependency-sources.zip'
    with ThreadPoolExecutor(max_workers=7) as pool:
        sources = list(pool.map(download, items))
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sources:
            archive.writestr(name, data)
        for relative in ['third-party-sources.lock.json', 'THIRD_PARTY_NOTICES.md', 'LICENSE']:
            archive.write(ROOT / relative, relative)
        for path in sorted((ROOT / 'LICENSES').iterdir()):
            archive.write(path, 'LICENSES/' + path.name)
    print(f'Packaged {len(sources)} dependency source archives: {destination}')


if __name__ == '__main__':
    main()
