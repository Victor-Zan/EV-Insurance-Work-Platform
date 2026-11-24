"""Root-owned release installer. Production secrets never cross the SSH boundary."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile

ROOT = Path('/var/lib/ev-insurance/deployment')
RELEASES = ROOT / 'releases'
BACKUPS = Path('/var/backups/ev-insurance/remote-deployment')
PUBLIC = Path('/home/ubuntu/ev-insurance-deploy/releases/directory-20261122/admin-dist')
UNIT = Path('/etc/systemd/system/ev-insurance-backend.service.d/90-release.conf')
MAINTENANCE = Path('/etc/ev-insurance/web-public/config/.deployment-maintenance')
ORIGIN = 'https://106.55.251.21'
REPOSITORY = 'Victor-Zan/EV-Insurance-Work-Platform'
MAX_FILES = 2000
MAX_EXPANDED = 300 * 1024 * 1024


class Rejected(RuntimeError):
    pass


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def atomic(path, data, mode=0o644):
    pending = path.with_name(path.name + '.pending')
    pending.write_bytes(data)
    pending.chmod(mode)
    os.replace(pending, path)


def manifest_path(name):
    p = PurePosixPath(name)
    if '\\' in name or p.is_absolute() or '..' in p.parts or '.' in p.parts or str(p) != name:
        raise Rejected('Invalid release path')
    if name in {'backend.jar', 'manifest.json'}:
        return p
    if len(p.parts) >= 2 and p.parts[0] in {'admin-dist', 'h5-dist'}:
        if any(part.startswith('.') for part in p.parts) or p.suffix.lower() not in {
            '.html', '.js', '.css', '.json', '.svg', '.png', '.jpg', '.jpeg', '.webp', '.ico', '.woff', '.woff2', '.ttf', '.txt', '.map', '.gif'}:
            raise Rejected('Unexpected frontend asset')
        return p
    raise Rejected('Unexpected release file')


def unpack(archive_path, stage, commit):
    with tarfile.open(archive_path, 'r:gz') as archive:
        seen = set()
        size = 0
        for member in archive:
            # No ownership, permissions, links, devices or tar extraction callbacks are trusted.
            if member.isdir():
                p = PurePosixPath(member.name)
                if p.parts and p.parts[0] in {'admin-dist', 'h5-dist'} and all(not s.startswith('.') for s in p.parts) and str(p) == member.name and '\\' not in member.name:
                    continue
                raise Rejected('Invalid release directory')
            if not member.isfile():
                raise Rejected('Release contains a link or special file')
            path = manifest_path(member.name)
            if member.name in seen:
                raise Rejected('Duplicate release entry')
            seen.add(member.name)
            size += member.size
            if len(seen) > MAX_FILES or size > MAX_EXPANDED:
                raise Rejected('Release size limit exceeded')
            target = stage / path
            target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
            with archive.extractfile(member) as source, target.open('xb') as output:
                shutil.copyfileobj(source, output)
            target.chmod(0o644)
    data = json.loads((stage / 'manifest.json').read_text())
    if data.get('format') != 1 or data.get('commit') != commit or data.get('repository') != REPOSITORY or data.get('branch') != 'main':
        raise Rejected('Release identity does not match')
    files = data.get('files', {})
    actual = {p.relative_to(stage).as_posix() for p in stage.rglob('*') if p.is_file()} - {'manifest.json'}
    if set(files) != actual or not {'backend.jar', 'admin-dist/index.html', 'admin-dist/build-info.json'} <= actual:
        raise Rejected('Release manifest is incomplete')
    for name, expected in files.items():
        manifest_path(name)
        if not re.fullmatch(r'[0-9a-f]{64}', str(expected)) or digest(stage / name) != expected:
            raise Rejected('Release checksum mismatch')
    info = json.loads((stage / 'admin-dist/build-info.json').read_text())
    if info.get('commit') != commit:
        raise Rejected('Frontend version does not match')
    return data


def migrations(jar):
    with zipfile.ZipFile(jar) as archive:
        names = [n for n in archive.namelist() if n.startswith('BOOT-INF/classes/db/') and n.endswith('.sql')]
        if len(names) != len(set(names)) or not names:
            raise Rejected('Invalid database migration archive')
        result = {}
        for name in names:
            if archive.getinfo(name).file_size > 1024 * 1024:
                raise Rejected('Migration entry exceeds limit')
            result[name] = hashlib.sha256(archive.read(name).replace(b'\r\n', b'\n')).hexdigest()
        return result


def require_same_schema(before, after):
    if migrations(before) != migrations(after):
        raise Rejected('Database migration changes require a separately rehearsed deployment; production was not changed')


def current_main():
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + '/git/ref/heads/main',
                                     headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'EV-Insurance-Deployment'})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)['object']['sha']


def run(args, log, output=None):
    result = subprocess.run(args, stdout=output or log, stderr=log, stdin=subprocess.DEVNULL)
    if result.returncode:
        raise Rejected('A deployment operation failed; private server logs retained')


def health():
    for _ in range(60):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8080/api/v1/health', timeout=2) as response:
                if json.load(response)['data']['status'] == 'UP':
                    return
        except Exception:
            pass
        time.sleep(1)
    raise Rejected('Backend startup health check failed')


def install_assets(release):
    for p in (release / 'admin-dist').rglob('*'):
        if not p.is_file() or p.name in {'index.html', 'build-info.json'}:
            continue
        target = PUBLIC / p.relative_to(release / 'admin-dist')
        if target.is_symlink() or not target.resolve().is_relative_to(PUBLIC.resolve()):
            raise Rejected('Unsafe public asset destination')
        target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        if target.exists():
            if digest(target) != digest(p):
                raise Rejected('Existing asset filename has different content')
        else:
            shutil.copyfile(p, target)
            target.chmod(0o644)


def activate(release, state, reason):
    name = release.name
    backup = BACKUPS / (time.strftime('%Y%m%d-%H%M%S') + '-' + name[:12])
    backup.mkdir(mode=0o700, parents=True, exist_ok=False)
    old_unit = UNIT.read_bytes()
    old_front = {name: (PUBLIC / name).read_bytes() for name in ['index.html', 'build-info.json']}
    (backup / '90-release.conf').write_bytes(old_unit)
    for filename, data in old_front.items():
        (backup / filename).write_bytes(data)
    shutil.copy2('/etc/ev-insurance/backend.env', backup / 'backend.env')
    (backup / 'backend.env').chmod(0o600)
    install_assets(release)
    MAINTENANCE.touch()
    switched = False
    reopen = False
    with (backup / 'operation.log').open('a') as log:
        try:
            run(['systemctl', 'stop', 'ev-insurance-backend'], log)
            with (backup / 'database.dump').open('xb') as output:
                run(['docker', 'exec', 'ev-insurance-postgres', 'pg_dump', '-Fc', '-U', 'ev_insurance', '-d', 'ev_insurance'], log, output)
            config = ('[Unit]\nDescription=EV Insurance release ' + name + '\n[Service]\nWorkingDirectory=' + str(release)
                      + '\nExecStart=\nExecStart=/usr/bin/java -Xms128m -Xmx768m -jar ' + str(release / 'backend.jar')
                      + '\nEnvironment=APP_BUILD_COMMIT=' + name + '\n')
            atomic(UNIT, config.encode())
            run(['systemctl', 'daemon-reload'], log)
            run(['systemctl', 'start', 'ev-insurance-backend'], log)
            health()
            for filename in old_front:
                atomic(PUBLIC / filename, (release / 'admin-dist' / filename).read_bytes())
            run(['curl', '-fsS', '--max-time', '15', '--resolve', '106.55.251.21:443:127.0.0.1', ORIGIN + '/api/v1/health'], log)
            raw = subprocess.check_output(['curl', '-fsS', '--max-time', '15', '--resolve', '106.55.251.21:443:127.0.0.1', ORIGIN + '/build-info.json'], stderr=log)
            if json.loads(raw).get('commit') != name:
                raise Rejected('Published frontend version check failed')
            new_state = {'current': name, 'previous': state['current'], 'updatedAt': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'operation': reason}
            atomic(ROOT / 'state.json', json.dumps(new_state, indent=2).encode(), 0o600)
            (backup / 'result.json').write_text(json.dumps(new_state))
            switched = True
            reopen = True
        except Exception:
            atomic(UNIT, old_unit)
            for filename, data in old_front.items():
                atomic(PUBLIC / filename, data)
            atomic(ROOT / 'state.json', json.dumps(state, indent=2).encode(), 0o600)
            run(['systemctl', 'daemon-reload'], log)
            run(['systemctl', 'restart', 'ev-insurance-backend'], log)
            health()
            reopen = True
            raise
        finally:
            if reopen:
                MAINTENANCE.unlink(missing_ok=True)
    if switched:
        print(json.dumps(new_state))


def operate(args):
    state = json.loads((ROOT / 'state.json').read_text())
    if args.command == 'status':
        print(json.dumps({**state, 'service': subprocess.check_output(['systemctl', 'is-active', 'ev-insurance-backend'], text=True).strip()}))
        return
    current = RELEASES / state['current']
    if args.command == 'rollback':
        if args.target != 'previous' or not state.get('previous'):
            raise Rejected('No previous release is available')
        target = RELEASES / state['previous']
        require_same_schema(current / 'backend.jar', target / 'backend.jar')
        activate(target, state, 'rollback')
        return
    if not re.fullmatch(r'[0-9a-f]{40}', args.target or ''):
        raise Rejected('A full commit SHA is required')
    if args.target != current_main():
        raise Rejected('Only the current main commit can be released; rebuild main')
    if args.command == 'publish' and args.target == state['current']:
        print('Already deployed: ' + args.target)
        return
    incoming = ROOT / 'incoming' / (args.target + '.tar.gz')
    if incoming.is_symlink() or not incoming.is_file() or incoming.stat().st_size > 180 * 1024 * 1024:
        raise Rejected('A valid bounded upload is required')
    # Copy bytes out of the upload account's directory before inspection/activation.
    with tempfile.TemporaryDirectory(prefix='stage-', dir=ROOT) as folder:
        stage = Path(folder)
        private_archive = stage / 'upload.tar.gz'
        fd = os.open(incoming, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, 'rb') as source, private_archive.open('xb') as output:
            total = 0
            while block := source.read(1024 * 1024):
                total += len(block)
                if total > 180 * 1024 * 1024:
                    raise Rejected('Upload exceeds limit')
                output.write(block)
        artifact = stage / 'release'
        artifact.mkdir(mode=0o755)
        unpack(private_archive, artifact, args.target)
        require_same_schema(current / 'backend.jar', artifact / 'backend.jar')
        if args.command == 'check':
            print('Release validated without changing production: ' + args.target)
            return
        target = RELEASES / args.target
        if target.exists():
            # Reuse only an identical root-owned artifact left by an interrupted attempt.
            if digest(target / 'manifest.json') != digest(artifact / 'manifest.json'):
                raise Rejected('This commit already has a different staged artifact')
        else:
            shutil.move(artifact, target)
            target.chmod(0o755)
        activate(target, state, 'publish')


def main():
    import fcntl
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['check', 'publish', 'status', 'rollback'])
    parser.add_argument('target', nargs='?')
    args = parser.parse_args()
    if args.command == 'status' and args.target:
        raise Rejected('Status takes no arguments')
    with (ROOT / 'release.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        operate(args)


if __name__ == '__main__':
    try:
        main()
    except (Rejected, BlockingIOError, OSError, ValueError, KeyError, zipfile.BadZipFile, tarfile.TarError) as error:
        # Only our fixed validation messages are returned to the deployment identity.
        print(str(error) if isinstance(error, Rejected) else 'Deployment failed; private server evidence retained.', file=sys.stderr)
        raise SystemExit(1)
