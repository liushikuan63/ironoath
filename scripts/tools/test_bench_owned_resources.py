"""Fault-injection coverage for resource creation replies and ownership changes."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from bench_owned_resources import OwnedResources


class CleanupTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.out = Path(self.directory.name)
        self.db_owned, self.db_empty, self.container_present = False, True, False
        self.labels = {'ironoath.bench.owner': 'run', 'codex.thread': 'thread'}
        self.removed, self.dropped, self.unavailable = [], 0, False
        self.resources = OwnedResources(self.out, self.docker, self.mongo,
            {'run': 'run', 'threadId': 'thread', 'database': 'ironoath_bot_bench_run',
             'redisContainer': 'owned', 'redisPort': 6381})

    def docker(self, *args):
        if self.unavailable:
            raise subprocess.TimeoutExpired(args, 30)
        if args[:2] == ('container', 'ls'):
            return 'shortid' if self.container_present else ''
        if args[0] == 'inspect':
            return json.dumps([{'Id': 'immutable', 'Name': '/owned', 'Config': {'Labels': self.labels}}])
        if args[:2] == ('rm', '-f'):
            self.removed.append(args[2]); self.container_present = False
            return args[2]
        raise AssertionError(args)

    def mongo(self, script):
        if self.unavailable:
            raise subprocess.TimeoutExpired('mongosh', 30)
        if 'dropDatabase' in script:
            self.assertTrue(self.db_owned)
            self.dropped += 1; self.db_owned = False; self.db_empty = True
            return {'ok': 1}
        return {'owned': self.db_owned, 'empty': self.db_empty}

    def cleanup(self, audit=lambda: None):
        return self.resources.cleanup(audit, lambda port: not self.container_present)

    def test_mongo_create_committed_then_reply_times_out(self):
        self.resources.attempting('mongo')
        self.assertTrue(json.loads((self.out / 'owner.actual.json').read_text())['mongoAttempted'])
        try:
            self.db_owned, self.db_empty = True, False
            raise subprocess.TimeoutExpired('insert-marker', 30)
        except subprocess.TimeoutExpired:
            result, errors = self.cleanup()
        self.assertEqual((result, errors, self.dropped), ({'mongoDropped': True}, [], 1))

    def test_redis_created_then_reply_times_out_without_id(self):
        self.resources.attempting('redis')
        pointer = json.loads((self.out / 'owner.actual.json').read_text())
        self.assertTrue(pointer['redisAttempted']); self.assertIsNone(pointer['redisContainerId'])
        try:
            self.container_present = True
            raise subprocess.TimeoutExpired('docker-run', 30)
        except subprocess.TimeoutExpired:
            result, errors = self.cleanup()
        self.assertEqual(self.removed, ['immutable'])
        self.assertEqual(result, {'redisRemoved': True, 'redisPortFree': True})
        self.assertEqual(errors, [])

    def test_foreign_mongo_data_is_preserved(self):
        self.resources.attempting('mongo'); self.db_empty = False
        result, errors = self.cleanup()
        self.assertFalse(result['mongoDropped']); self.assertEqual(self.dropped, 0)
        self.assertEqual(len(errors), 1)

    def test_foreign_redis_label_is_preserved(self):
        self.resources.attempting('redis'); self.container_present = True
        self.labels['ironoath.bench.owner'] = 'other'
        result, errors = self.cleanup()
        self.assertFalse(result['redisRemoved']); self.assertEqual(self.removed, [])
        self.assertEqual(len(errors), 1)

    def test_same_label_replacement_id_is_preserved(self):
        self.resources.attempting('redis'); self.container_present = True
        self.resources.acknowledge(redisContainerId='old-id')
        result, errors = self.cleanup()
        self.assertFalse(result['redisRemoved']); self.assertEqual(self.removed, [])
        self.assertEqual(len(errors), 1)

    def test_unavailable_probe_is_unverified_not_absent(self):
        self.resources.attempting('mongo'); self.resources.attempting('redis')
        self.unavailable = True
        result, errors = self.cleanup()
        self.assertFalse(result['mongoDropped']); self.assertFalse(result['redisRemoved'])
        self.assertEqual(len(errors), 2); self.assertEqual(self.removed, [])

    def test_failed_creation_with_no_resources_is_verified_clear(self):
        self.resources.attempting('mongo'); self.resources.attempting('redis')
        result, errors = self.cleanup()
        self.assertTrue(all(result.values())); self.assertEqual(errors, [])

    def test_audit_failure_does_not_prevent_guarded_owned_drop(self):
        self.resources.attempting('mongo'); self.db_owned, self.db_empty = True, False
        def failed_audit():
            raise RuntimeError('audit unavailable')
        result, errors = self.cleanup(failed_audit)
        self.assertTrue(result['mongoDropped']); self.assertEqual(self.dropped, 1)
        self.assertEqual(errors[0]['stage'], 'mongo-audit')


if __name__ == '__main__':
    unittest.main()
