"""Build a secret-free release archive from already verified build outputs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile


def package(source, output, commit):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('A full commit SHA is required')
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
    if actual != commit:
        raise ValueError('Checkout and requested release do not match')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as folder:
        stage = Path(folder)
        shutil.copyfile(source / 'backend/target/backend-0.0.1-SNAPSHOT.jar', stage / 'backend.jar')
        for name, original in [('admin-dist', 'admin-web/dist'), ('h5-dist', 'h5-web/dist')]:
            shutil.copytree(source / original, stage / name)
        info = {'commit': commit, 'branch': 'main', 'repository': 'Victor-Zan/EV-Insurance-Work-Platform'}
        (stage / 'admin-dist/build-info.json').write_text(json.dumps(info), encoding='utf-8')
        files = {p.relative_to(stage).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in stage.rglob('*') if p.is_file()}
        manifest = {'format': 1, **info, 'files': files}
        (stage / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        with tarfile.open(output, 'w:gz') as archive:
            for p in stage.iterdir():
                archive.add(p, arcname=p.name)
    print(json.dumps({'commit': commit, 'archiveBytes': output.stat().st_size}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--commit', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    package(Path(__file__).resolve().parents[2], args.output, args.commit)
