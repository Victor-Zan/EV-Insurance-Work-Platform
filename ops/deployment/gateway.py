"""Forced SSH command: no shell, SCP, SFTP, arbitrary paths or forwarding."""
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

MAX_UPLOAD = 180 * 1024 * 1024
INCOMING = Path('/var/lib/ev-insurance-deployment/incoming')
PUBLISHER = '/usr/local/sbin/ev-insurance-release'


def parse(command):
    parts = shlex.split(command)
    if parts == ['status'] or parts == ['rollback', 'previous']:
        return parts
    if len(parts) == 2 and parts[0] in {'upload', 'check', 'publish'} and re.fullmatch(r'[0-9a-f]{40}', parts[1]):
        return parts
    raise ValueError('Allowed: upload/check/publish COMMIT, status, rollback previous')


def upload(commit, stream):
    import fcntl
    with open(INCOMING / '.upload.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        fd, filename = tempfile.mkstemp(prefix='upload-', dir=INCOMING)
        count = 0
        try:
            with os.fdopen(fd, 'wb') as output:
                while chunk := stream.read(1024 * 1024):
                    count += len(chunk)
                    if count > MAX_UPLOAD:
                        raise ValueError('Release exceeds upload limit')
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if count == 0:
                raise ValueError('Empty upload')
            # Retain only two other packages, bounding storage used by this identity.
            others = sorted(INCOMING.glob('*.tar.gz'), key=lambda p: p.stat().st_mtime, reverse=True)
            for p in others[2:]:
                p.unlink()
            os.replace(filename, INCOMING / (commit + '.tar.gz'))
        finally:
            Path(filename).unlink(missing_ok=True)
    print('Upload received: ' + commit)


def main():
    os.umask(0o077)
    args = parse(os.environ.get('SSH_ORIGINAL_COMMAND', ''))
    if args[0] == 'upload':
        upload(args[1], sys.stdin.buffer)
    else:
        raise SystemExit(subprocess.call(['sudo', '-n', PUBLISHER, *args], stdin=subprocess.DEVNULL))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError):
        print('Deployment request rejected. No interactive shell is available.', file=sys.stderr)
        raise SystemExit(2)
