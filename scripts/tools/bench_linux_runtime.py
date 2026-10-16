"""Small fail-closed authorities for the separate Linux acceptance runner."""
import ipaddress
import json


class OwnedLinuxContainer:
    """A Java container is identified independently from the host Docker CLI PID."""
    def __init__(self, out, docker, run, thread, name, image):
        self.out, self.docker = out, docker
        self.intent = {'run': run, 'threadId': thread, 'name': name, 'image': image,
                       'containerId': None, 'role': 'owned-linux-java-container'}
        self.save()

    def save(self):
        path = self.out / 'java-owner.actual.json'
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.intent, indent=2), encoding='utf8')
        temporary.replace(path)

    def acknowledge(self, identifier):
        self.intent['containerId'] = identifier
        self.save()

    def reconcile(self):
        name, known = self.intent['name'], self.intent['containerId']
        found = self.docker('container', 'ls', '-a', '--filter', 'name=^/' + name + '$', '--format', '{{.ID}}')
        if not found:
            if known and self.docker('container', 'ls', '-a', '--filter', 'id=' + known, '--format', '{{.ID}}'):
                raise RuntimeError('Known Java container was renamed; preserve it')
            return None
        if len(found.splitlines()) != 1:
            raise RuntimeError('Ambiguous Java container name; preserve all matches')
        details = json.loads(self.docker('inspect', name))[0]
        labels = details['Config'].get('Labels') or {}
        if (details['Name'] != '/' + name or details['Image'] != self.intent['image']
                or labels.get('ironoath.bench.owner') != self.intent['run']
                or labels.get('codex.thread') != self.intent['threadId']
                or (known is not None and details['Id'] != known)):
            raise RuntimeError('Java container ownership changed; preserve it')
        self.acknowledge(details['Id'])
        return details['Id']

    def remove(self):
        identifier = self.reconcile()
        if identifier is not None:
            try:
                self.docker('rm', '-f', identifier)
            except Exception as error:
                if self.reconcile() is not None:
                    raise
                self.intent['lostRemovalReplyReadBackAbsent'] = type(error).__name__
                self.save()
        return self.reconcile() is None


def bridge_endpoints(mongo, redis):
    def networks(details):
        return {value['NetworkID']: value for value in details['NetworkSettings']['Networks'].values()
                if value.get('NetworkID')}
    first, second = networks(mongo), networks(redis)
    common = first.keys() & second.keys()
    if len(common) != 1:
        raise RuntimeError('Exactly one existing common network is required; do not reconnect original Mongo')
    network = next(iter(common))
    addresses = [first[network].get('IPAddress', ''), second[network].get('IPAddress', '')]
    for address in addresses:
        parsed = ipaddress.ip_address(address)
        if (parsed.version != 4 or not parsed.is_private or parsed.is_loopback
                or parsed.is_unspecified or parsed.is_link_local or parsed.is_multicast):
            raise RuntimeError('Expected a private existing Docker bridge IPv4 endpoint')
    return {'networkId': network, 'mongoIp': addresses[0], 'redisIp': addresses[1]}


def guest_environment(source):
    names = ['SPRING_PROFILES_ACTIVE', 'BOT_BENCH_OUT', 'BOT_BENCH_BUSINESS_NOW',
             'BOT_BENCH_MONGO_TRANSPORT', 'BOT_BENCH_DATABASE', 'SPRING_APPLICATION_JSON']
    result = {name: source[name] for name in names if name in source}
    if 'SPRING_APPLICATION_JSON' in result:
        properties = json.loads(result['SPRING_APPLICATION_JSON'])
        if properties != {'ironoath.bot.calibration-workers': 4}:
            raise RuntimeError('Unexpected guest Spring properties; values withheld')
    result['BOT_BENCH_OUT'] = '/out'
    return result
