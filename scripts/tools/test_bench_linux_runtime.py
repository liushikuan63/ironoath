"""Linux acceptance resource authority, independent of Docker availability."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import ast
import hashlib
import time
import platform
from types import SimpleNamespace

if os.environ.get('IRONOATH_LINUX_OWNER_BASELINE') == '1':
    from bench_owned_resources import OwnedResources

    class OwnedLinuxContainer:
        """Explicit existing606 reference for the single initial red, never a fallback."""
        def __init__(self, out, docker, run, thread, name, image):
            self.legacy = OwnedResources(out, docker, None,
                {'run': run, 'threadId': thread, 'redisContainer': name, 'redisPort': 9895})
        def acknowledge(self, identifier):
            self.legacy.acknowledge(redisContainerId=identifier)
        def reconcile(self):
            return self.legacy.container()
else:
    from bench_linux_runtime import OwnedLinuxContainer, bridge_endpoints, guest_environment


class ContainerAuthorityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.out = Path(self.directory.name)
        self.details = {'Id': 'immutable', 'Name': '/owned', 'Image': 'image',
            'Config': {'Labels': {'ironoath.bench.owner': 'run', 'codex.thread': 'thread'}}}
        self.removed = []
        self.unavailable = False
        self.ambiguous = False
        self.remove_reply_lost = False
        self.owner = OwnedLinuxContainer(self.out, self.docker, 'run', 'thread', 'owned', 'image')

    def docker(self, *args):
        if self.unavailable:
            raise subprocess.TimeoutExpired('docker', 30)
        if args[:2] == ('container', 'ls'):
            if self.ambiguous:
                return 'first\nsecond'
            criterion = args[args.index('--filter') + 1]
            return 'shortid' if self.details and (
                criterion == 'name=^/' + self.details['Name'][1:] + '$'
                or criterion == 'id=' + self.details['Id']) else ''
        if args[0] == 'inspect':
            self.assertIsNotNone(self.details)
            return json.dumps([self.details])
        if args[:2] == ('rm', '-f'):
            self.assertEqual(args[2], self.details['Id'])
            self.removed.append(args[2]); self.details = None
            if self.remove_reply_lost:
                raise subprocess.TimeoutExpired('docker-rm', 30)
            return args[2]
        raise AssertionError(args)

    def test_known_id_renamed_is_not_reported_absent(self):
        self.owner.acknowledge('immutable')
        self.details['Name'] = '/renamed'
        with self.assertRaises(RuntimeError):
            self.owner.reconcile()
        self.assertEqual(self.removed, [])
        self.assertIsNotNone(self.details)

    def test_lost_create_reply_recovers_exact_owned_id(self):
        self.assertEqual(self.owner.reconcile(), 'immutable')
        self.assertEqual(self.owner.reconcile(), 'immutable')

    def test_owned_removal_uses_immutable_id_and_verifies_absence(self):
        self.assertTrue(self.owner.remove())
        self.assertEqual(self.removed, ['immutable'])
        self.assertIsNone(self.owner.reconcile())

    def test_lost_remove_reply_is_read_back_instead_of_repeated_blind_deletion(self):
        self.remove_reply_lost = True
        self.assertTrue(self.owner.remove())
        self.assertEqual(self.removed, ['immutable'])

    def test_foreign_owner_is_preserved(self):
        self.details['Config']['Labels']['ironoath.bench.owner'] = 'foreign'
        with self.assertRaises(RuntimeError):
            self.owner.remove()
        self.assertEqual(self.removed, [])

    def test_foreign_thread_is_preserved(self):
        self.details['Config']['Labels']['codex.thread'] = 'another-thread'
        with self.assertRaises(RuntimeError):
            self.owner.remove()
        self.assertEqual(self.removed, [])

    def test_same_name_replaced_id_is_preserved(self):
        self.owner.acknowledge('previous-id')
        with self.assertRaises(RuntimeError):
            self.owner.remove()
        self.assertEqual(self.removed, [])

    def test_wrong_image_is_preserved_even_if_labels_match(self):
        self.details['Image'] = 'different-image'
        with self.assertRaises(RuntimeError):
            self.owner.remove()
        self.assertEqual(self.removed, [])

    def test_unavailable_authority_is_not_treated_as_absence(self):
        self.unavailable = True
        with self.assertRaises(subprocess.TimeoutExpired):
            self.owner.remove()
        self.assertEqual(self.removed, [])

    def test_multiple_name_matches_fail_closed(self):
        self.ambiguous = True
        with self.assertRaises(RuntimeError):
            self.owner.remove()
        self.assertEqual(self.removed, [])


class RunnerCleanupTest(unittest.TestCase):
    """Execute the real runner finally block, without starting its CLI/services."""
    def run_finally(self, fault, foreign_java=False):
        source = Path(__file__).with_name('run-bot-runtime-bench-linux.py')
        tree = ast.parse(source.read_text(encoding='utf8'))
        body = next(node.finalbody for node in tree.body
                    if isinstance(node, ast.Try) and node.finalbody)
        calls = []

        class Attach:
            def poll(self):
                if fault == 'poll':
                    raise OSError('attach poll failed')
                return None
            def terminate(self):
                calls.append('terminate')
                if fault == 'terminate':
                    raise OSError('attach terminate failed')
            def wait(self, timeout):
                calls.append(('wait', timeout))
                raise subprocess.TimeoutExpired('owned-docker-attach', timeout)

        class Authority:
            intent = {'containerId': 'owned-java'}
            def __init__(self, key):
                self.key = key
            def remove(self):
                calls.append(self.key)
                if foreign_java and self.key == 'java':
                    raise RuntimeError('Java container ownership changed; preserve it')
                return True

        class Resources:
            intent = {'redisAttempted': True}
            def container(self):
                return None
            def cleanup(self, audit, free):
                calls.extend(['mongo-cleanup', 'redis-cleanup'])
                return {'mongoDropped': True, 'redisRemoved': True, 'redisPortFree': True}, []

        with tempfile.TemporaryDirectory() as directory:
            env = dict(process=Attach(), errors=[], native_cleanup={}, code=0,
                java_owner=Authority('java'), probe=Authority('guard'),
                control_status=[], free=lambda: True, resources=Resources(),
                args=SimpleNamespace(storage='mongo-redis', mongo_transport='netty', business_now=1),
                mongo=lambda script: {'absent': True}, run='owned', out=Path(directory),
                mongo_before={'Id': 'original', 'State': {'StartedAt': 'same'}},
                docker=lambda *args: json.dumps([{'Id': 'original', 'State': {'Running': True, 'StartedAt': 'same'}}]),
                tool=source, resources_file=source, linux_owner_file=source,
                original_runner_file=source, port_guard=source,
                tool_sha=hashlib.sha256(source.read_bytes()).hexdigest(),
                resources_sha=hashlib.sha256(source.read_bytes()).hexdigest(),
                linux_owner_sha=hashlib.sha256(source.read_bytes()).hexdigest(),
                original_runner_sha=hashlib.sha256(source.read_bytes()).hexdigest(),
                port_guard_sha=hashlib.sha256(source.read_bytes()).hexdigest(),
                runner_sha=hashlib.sha256(source.read_bytes()).hexdigest(),
                __file__=str(source), sha='candidate', config_hashes={},
                java_image='cached-image', bridge=None, start=time.time(),
                hashlib=hashlib, time=time, platform=platform, json=json)
            escaped = None
            try:
                exec(compile(ast.Module(body=body, type_ignores=[]), str(source), 'exec'), env)
            except Exception as error:
                escaped = error
            self.assertIn('mongo-cleanup', calls,
                          f'attach {fault} skipped remaining cleanup: {escaped!r}')
            self.assertIn('redis-cleanup', calls)
            self.assertIn('java', calls)
            self.assertIn('guard', calls)
            self.assertIsNone(escaped, repr(escaped))
            receipt = json.loads((Path(directory)/'runner-result.actual.json').read_text())
            self.assertEqual(receipt['exit'], 1)
            self.assertTrue(receipt['cleanup']['mongoDropped'])
            self.assertTrue(receipt['cleanup']['redisRemoved'])
            self.assertFalse(receipt['cleanup']['hostDockerAttachExited'])
            self.assertTrue(any(e.get('stage') == 'host-docker-attach-cleanup'
                                for e in receipt['errors']))
            self.assertEqual(receipt['cleanup']['javaContainerRemoved'], not foreign_java)
            return receipt

    def test_attach_wait_timeout_still_cleans_all_owners_and_writes_failure(self):
        self.run_finally('wait')

    def test_attach_terminate_oserror_still_cleans_all_owners_and_writes_failure(self):
        self.run_finally('terminate')

    def test_attach_poll_oserror_still_cleans_all_owners_and_writes_failure(self):
        self.run_finally('poll')

    def test_attach_failure_preserves_foreign_java_and_continues_other_cleanup(self):
        self.run_finally('wait', foreign_java=True)


class GuestMappingTest(unittest.TestCase):
    def endpoint(self, network, address):
        return {'NetworkSettings': {'Networks': {
            network: {'NetworkID': 'network-id', 'IPAddress': address}}}}

    def test_existing_common_bridge_returns_exact_private_addresses(self):
        result = bridge_endpoints(self.endpoint('bridge', '172.17.0.2'),
                                  self.endpoint('bridge', '172.17.0.3'))
        self.assertEqual(result, {'networkId': 'network-id', 'mongoIp': '172.17.0.2', 'redisIp': '172.17.0.3'})

    def test_missing_common_network_is_rejected(self):
        other = self.endpoint('bridge', '172.17.0.3')
        other['NetworkSettings']['Networks']['bridge']['NetworkID'] = 'different'
        with self.assertRaises(RuntimeError):
            bridge_endpoints(self.endpoint('bridge', '172.17.0.2'), other)

    def test_ambiguous_common_networks_are_rejected(self):
        first = self.endpoint('bridge', '172.17.0.2')
        second = self.endpoint('bridge', '172.17.0.3')
        for endpoint in [first, second]:
            endpoint['NetworkSettings']['Networks']['another'] = {'NetworkID': 'another', 'IPAddress': '172.18.0.4'}
        with self.assertRaises(RuntimeError):
            bridge_endpoints(first, second)

    def test_public_or_loopback_endpoint_is_rejected(self):
        for address in ['8.8.8.8', '127.0.0.1', '0.0.0.0', 'not-an-ip']:
            with self.subTest(address=address), self.assertRaises((RuntimeError, ValueError)):
                bridge_endpoints(self.endpoint('bridge', address), self.endpoint('bridge', '172.17.0.3'))

    def test_only_controlled_environment_is_passed_to_guest(self):
        source = {'SECRET': 'must-not-pass', 'HTTP_PROXY': 'must-not-pass',
            'BOT_BENCH_OUT': 'windows-path', 'BOT_BENCH_BUSINESS_NOW': '1791979200000',
            'BOT_BENCH_MONGO_TRANSPORT': 'netty', 'BOT_BENCH_DATABASE': 'owned',
            'SPRING_PROFILES_ACTIVE': 'dev', 'SPRING_APPLICATION_JSON': '{"ironoath.bot.calibration-workers":4}'}
        result = guest_environment(source)
        self.assertNotIn('SECRET', result); self.assertNotIn('HTTP_PROXY', result)
        self.assertEqual(result['BOT_BENCH_OUT'], '/out')
        self.assertEqual(result['BOT_BENCH_MONGO_TRANSPORT'], 'netty')
        self.assertEqual(result['SPRING_APPLICATION_JSON'], source['SPRING_APPLICATION_JSON'])

    def test_unexpected_spring_properties_are_not_forwarded(self):
        with self.assertRaises(RuntimeError):
            guest_environment({'SPRING_APPLICATION_JSON': '{"password":"must-not-pass"}'})


if __name__ == '__main__':
    unittest.main()
