import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / (name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


publisher = module('publisher')
gateway = module('gateway')
SHA = 'a' * 40


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, extra=None, checksum=None):
        files = {'backend.jar': b'jar', 'admin-dist/index.html': b'html',
                 'admin-dist/build-info.json': json.dumps({'commit': SHA}).encode()}
        manifest = {'format': 1, 'commit': SHA, 'repository': publisher.REPOSITORY, 'branch': 'main',
                    'files': {k: hashlib.sha256(v).hexdigest() for k, v in files.items()}}
        if checksum:
            manifest['files']['backend.jar'] = checksum
        files['manifest.json'] = json.dumps(manifest).encode()
        path = self.root / 'release.tar.gz'
        with tarfile.open(path, 'w:gz') as archive:
            for name, body in files.items():
                member = tarfile.TarInfo(name)
                member.size = len(body)
                archive.addfile(member, io.BytesIO(body))
            if extra:
                member, body = extra
                archive.addfile(member, io.BytesIO(body))
        stage = self.root / 'stage'
        stage.mkdir()
        return path, stage

    def test_forced_command_rejects_shell_subsystem_arguments_and_path_injection(self):
        for command in ['', 'sh', 'sftp', 'scp -t /etc', 'status; id', 'publish ../etc', 'rollback /etc',
                        'publish ' + SHA + ' extra', 'status extra', 'upload $(id)', 'publish ' + 'A' * 40]:
            with self.subTest(command=command), self.assertRaises(ValueError):
                gateway.parse(command)
        for command in ['status', 'rollback previous', 'publish ' + SHA, 'check ' + SHA, 'upload ' + SHA]:
            self.assertEqual(gateway.parse(command), command.split())

    def test_valid_archive_binds_commit_and_checks_all_file_hashes(self):
        archive, stage = self.archive()
        self.assertEqual(publisher.unpack(archive, stage, SHA)['commit'], SHA)

    def test_archive_cannot_escape_stage(self):
        for name in ['../escape', '/etc/overwrite', 'admin-dist/../../escape', 'admin-dist/.env', 'admin-dist\\escape']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                old = self.root
                self.root = Path(directory)
                member = tarfile.TarInfo(name)
                member.size = 1
                archive, stage = self.archive((member, b'x'))
                with self.assertRaises(publisher.Rejected):
                    publisher.unpack(archive, stage, SHA)
                self.assertFalse((self.root / 'escape').exists())
                self.root = old

    def test_links_and_duplicate_entries_are_rejected(self):
        for kind in [tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.REGTYPE]:
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                old = self.root
                self.root = Path(directory)
                member = tarfile.TarInfo('backend.jar')
                member.type = kind
                member.linkname = '/etc/passwd'
                archive, stage = self.archive((member, b''))
                with self.assertRaises(publisher.Rejected):
                    publisher.unpack(archive, stage, SHA)
                self.root = old

    def test_wrong_commit_or_corrupt_asset_never_passes_validation(self):
        archive, stage = self.archive(checksum='0' * 64)
        with self.assertRaisesRegex(publisher.Rejected, 'checksum'):
            publisher.unpack(archive, stage, SHA)

    def test_archive_expansion_limit_is_enforced(self):
        archive, stage = self.archive()
        with patch.object(publisher, 'MAX_EXPANDED', 1), self.assertRaises(publisher.Rejected):
            publisher.unpack(archive, stage, SHA)

    def jar(self, name, sql):
        path = self.root / name
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('BOOT-INF/classes/db/migration/V1__base.sql', sql)
        return path

    def test_schema_changes_block_both_publish_and_rollback(self):
        a = self.jar('old.jar', 'select 1;\r\n')
        same = self.jar('same.jar', 'select 1;\n')
        changed = self.jar('changed.jar', 'select 2;\n')
        publisher.require_same_schema(a, same)
        with self.assertRaisesRegex(publisher.Rejected, 'separately rehearsed'):
            publisher.require_same_schema(a, changed)

    def test_backend_failure_restores_unit_frontend_and_state_before_reopening(self):
        self.activation_test(rollback_healthy=True)

    def test_failed_rollback_keeps_public_writes_in_maintenance(self):
        self.activation_test(rollback_healthy=False)

    def test_mobile_assets_install_beside_management_without_overwriting_it(self):
        public = self.root / 'public'
        public.mkdir()
        (public / 'index.html').write_bytes(b'management')
        release = self.root / 'release'
        for portal in ['admin-dist', 'h5-dist']:
            (release / portal / 'assets').mkdir(parents=True)
            (release / portal / 'assets/app.js').write_bytes(portal.encode())
        (release / 'h5-dist/build-info.json').write_text('{}')
        with patch.object(publisher, 'PUBLIC', public):
            publisher.install_assets(release)
        self.assertEqual((public / 'index.html').read_bytes(), b'management')
        self.assertEqual((public / 'assets/app.js').read_bytes(), b'admin-dist')
        self.assertEqual((public / 'mobile/assets/app.js').read_bytes(), b'h5-dist')

    def test_changed_schema_cannot_be_approved_by_an_unmatched_rehearsal(self):
        before = self.jar('before.jar', 'select 1;')
        after = self.jar('after.jar', 'select 2;')
        with patch.object(publisher, 'ROOT', self.root):
            with self.assertRaisesRegex(publisher.Rejected, 'rehearsal approval'):
                publisher.require_rehearsed_schema(before, after, SHA)
        # The ordinary rollback route continues to reject schema differences.
        with self.assertRaises(publisher.Rejected):
            publisher.require_same_schema(after, before)

    def test_backend_failure_also_restores_the_mobile_entry_and_version(self):
        self.activation_test(rollback_healthy=True, mobile=True)

    def test_root_rehearsal_approves_only_the_exact_current_and_target_jar(self):
        before = self.jar('before.jar', 'select 1;')
        after = self.jar('after.jar', 'select 2;')
        approval = self.root / 'approved-migrations' / (SHA + '.json')
        approval.parent.mkdir()
        approval.write_text(json.dumps({'from': before.parent.name, 'to': SHA,
            'before': publisher.migrations(before), 'after': publisher.migrations(after),
            'jarSha256': publisher.digest(after), 'rehearsalPassed': True}))
        original_stat = Path.stat
        def trusted_stat(path, *args, **kwargs):
            result = original_stat(path, *args, **kwargs)
            if path == approval:
                fields = list(result)
                fields[0] &= ~0o022
                fields[4] = 0
                return os.stat_result(fields)
            return result
        with patch.object(publisher, 'ROOT', self.root), patch.object(Path, 'stat', trusted_stat):
            publisher.require_rehearsed_schema(before, after, SHA)
            # Same SQL is insufficient when the approved application JAR changes.
            with zipfile.ZipFile(after, 'a') as jar:
                jar.writestr('BOOT-INF/classes/application.yml', 'changed application')
            with self.assertRaisesRegex(publisher.Rejected, 'exact release'):
                publisher.require_rehearsed_schema(before, after, SHA)

    def activation_test(self, rollback_healthy, mobile=False):
        public = self.root / 'public'
        public.mkdir()
        for name in ['index.html', 'build-info.json']:
            (public / name).write_bytes(b'old')
        unit = self.root / 'unit.conf'
        unit.write_bytes(b'old unit')
        state = {'current': 'baseline', 'previous': None}
        (self.root / 'state.json').write_text(json.dumps(state))
        release = self.root / SHA
        (release / 'admin-dist').mkdir(parents=True)
        for name in ['index.html', 'build-info.json']:
            (release / 'admin-dist' / name).write_bytes(b'new')
        if mobile:
            (public / 'mobile').mkdir()
            (release / 'h5-dist').mkdir()
            for name in ['index.html', 'build-info.json']:
                (public / 'mobile' / name).write_bytes(b'old mobile')
                (release / 'h5-dist' / name).write_bytes(b'new mobile')
        maintenance = self.root / 'maintenance'
        effects = [publisher.Rejected('synthetic startup failure'), None if rollback_healthy else publisher.Rejected('synthetic rollback failure')]
        with patch.multiple(publisher, ROOT=self.root, PUBLIC=public, UNIT=unit, MAINTENANCE=maintenance,
                            BACKUPS=self.root / 'backups'), patch.object(publisher, 'install_assets'), \
                patch.object(publisher, 'run'), patch.object(publisher.shutil, 'copy2'), \
                patch.object(publisher.Path, 'chmod'), patch.object(publisher, 'health', side_effect=effects):
            # copy2 is mocked because the test must never read a real production environment file.
            with self.assertRaises(publisher.Rejected):
                publisher.activate(release, state, 'publish')
        self.assertEqual(unit.read_bytes(), b'old unit')
        self.assertEqual((public / 'index.html').read_bytes(), b'old')
        self.assertEqual(json.loads((self.root / 'state.json').read_text()), state)
        self.assertEqual(maintenance.exists(), not rollback_healthy)
        if mobile:
            self.assertEqual((public / 'mobile/index.html').read_bytes(), b'old mobile')
            self.assertEqual((public / 'mobile/build-info.json').read_bytes(), b'old mobile')


if __name__ == '__main__':
    unittest.main()
