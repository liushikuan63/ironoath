"""Reconcile finite benchmark resources, including lost creation acknowledgements."""
import json


class OwnedResources:
    def __init__(self, out, docker, mongo, intent):
        self.out, self.docker, self.mongo = out, docker, mongo
        self.intent = dict(intent, mongoAttempted=False, redisAttempted=False,
                           redisContainerId=None, pid=None)
        self.save()

    def save(self):
        path = self.out / 'owner.actual.json'
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.intent, indent=2), encoding='utf8')
        temporary.replace(path)

    def attempting(self, resource):
        self.intent[resource + 'Attempted'] = True
        self.save()

    def acknowledge(self, **values):
        self.intent.update(values)
        self.save()

    def container(self):
        name = self.intent['redisContainer']
        found = self.docker('container', 'ls', '-a', '--filter', 'name=^/' + name + '$', '--format', '{{.ID}}')
        if not found:
            return None
        details = json.loads(self.docker('inspect', name))[0]
        labels = details['Config'].get('Labels') or {}
        known = self.intent['redisContainerId']
        if (details['Name'] != '/' + name or
                labels.get('ironoath.bench.owner') != self.intent['run'] or
                labels.get('codex.thread') != self.intent['threadId'] or
                (known is not None and details['Id'] != known)):
            raise RuntimeError('Container ownership changed; preserve existing owner')
        self.acknowledge(redisContainerId=details['Id'])
        return details['Id']

    def cleanup(self, audit, free):
        result, errors = {}, []
        run = json.dumps(self.intent['run'])
        if self.intent['mongoAttempted']:
            result['mongoDropped'] = False
            try:
                state = self.mongo('print(JSON.stringify({empty:d.getCollectionNames().length===0,owned:!!d.getCollection("__owned_run").findOne({_id:' + run + '})}));')
                if state['owned']:
                    try:
                        audit()
                    except Exception as error:
                        errors.append({'type': type(error).__name__, 'message': str(error), 'stage': 'mongo-audit'})
                    dropped = self.mongo('if(!d.getCollection("__owned_run").findOne({_id:' + run + '}))throw Error("Owner mismatch");print(JSON.stringify(d.dropDatabase()));')
                    result['mongoDropped'] = dropped.get('ok') == 1
                elif state['empty']:
                    result['mongoDropped'] = True
                else:
                    raise RuntimeError('Database ownership unverified; preserve existing data')
            except Exception as error:
                errors.append({'type': type(error).__name__, 'message': str(error), 'stage': 'mongo-cleanup'})
        if self.intent['redisAttempted']:
            result['redisRemoved'] = False
            try:
                owned_id = self.container()
                if owned_id is not None:
                    self.docker('rm', '-f', owned_id)
                result['redisRemoved'] = self.container() is None
            except Exception as error:
                errors.append({'type': type(error).__name__, 'message': str(error), 'stage': 'redis-cleanup'})
            result['redisPortFree'] = free(self.intent['redisPort'])
        self.acknowledge(cleanup=result, cleanupErrors=errors)
        return result, errors
