"""Linux deployment acceptance of the unchanged finite B11 fixture; separate runner/ownership evidence."""
from pathlib import Path
import argparse,hashlib,json,os,platform,socket,subprocess,time,uuid,zipfile,sys
from bench_owned_resources import OwnedResources
from bench_linux_runtime import OwnedLinuxContainer, bridge_endpoints, guest_environment

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--candidate',type=Path,required=True,help='Frozen directory: city-runtime.jar, config/, inputs.json')
parser.add_argument('--out',type=Path,required=True,help='New evidence directory; existing paths are preserved')
parser.add_argument('--java-bin',type=Path,required=True,help='Project JDK 17 bin directory')
parser.add_argument('--port',type=int,default=9885)
parser.add_argument('--business-now',type=int,required=True,help='Fixed positive business timestamp; performance budget remains real nanoTime')
parser.add_argument('--storage',choices=['memory','mongo-redis'],default='memory')
parser.add_argument('--redis-port',type=int,default=6381,help='New owned Redis; existing owner is never stopped')
parser.add_argument('--mongo-transport',choices=['socket','netty'],default='socket',help='Explicit transport experiment; default stays unchanged')
parser.add_argument('--java-image',default='eclipse-temurin:17-jre',help='Already cached Linux JRE17 image; never pulled')
args=parser.parse_args()
if sys.flags.optimize:raise RuntimeError('Benchmark safety assertions must remain enabled')
root=Path(__file__).resolve().parents[2];candidate=args.candidate.resolve();out=args.out.resolve()
assert 1024<=args.port<=65535 and args.port not in [9490,27017] and args.business_now>0
jar=candidate/'city-runtime.jar';inputs=json.loads((candidate/'inputs.json').read_text(encoding='utf8'))
sha=hashlib.sha256(jar.read_bytes()).hexdigest();assert sha==inputs['jarSha256'],'Frozen JAR SHA mismatch'
config_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (candidate/'config').glob('*.json')}
assert config_hashes==inputs['configHashes'],'Frozen config SHA mismatch'
def free(port=None):
    with socket.socket() as sock:return sock.connect_ex(('127.0.0.1',args.port if port is None else port))!=0
assert free(),'Benchmark port belongs to an existing process; preserved'
assert 1024<=args.redis_port<=65535 and args.redis_port not in [9490,27017] and args.redis_port!=args.port
if args.storage=='mongo-redis':assert free(args.redis_port),'Redis port belongs to an existing process; preserved'
out.mkdir(parents=True,exist_ok=False)
unpacked=out/'unpacked';unpacked.mkdir();classes=out/'fixture-classes';classes.mkdir()
with zipfile.ZipFile(jar) as archive:
    for name in archive.namelist():
        if not name.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')) or name.endswith('/'):continue
        target=(unpacked/name).resolve();assert target.is_relative_to(unpacked.resolve())
        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(name))
windows=os.name=='nt';suffix='.exe' if windows else '';flags=subprocess.CREATE_NO_WINDOW if windows else 0
classpath=os.pathsep.join([str(unpacked/'BOOT-INF/classes'),str(unpacked/'BOOT-INF/lib/*')])
tool=root/'tools/BotRuntimeBench.java';tool_sha=hashlib.sha256(tool.read_bytes()).hexdigest()
runner_sha=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();(out/'BotRuntimeBench.input.java').write_bytes(tool.read_bytes())
resources_file=Path(__file__).with_name('bench_owned_resources.py')
resources_sha=hashlib.sha256(resources_file.read_bytes()).hexdigest()
linux_owner_file=Path(__file__).with_name('bench_linux_runtime.py')
linux_owner_sha=hashlib.sha256(linux_owner_file.read_bytes()).hexdigest()
original_runner_file=root/'scripts/tools/run-bot-runtime-bench.py'
original_runner_sha=hashlib.sha256(original_runner_file.read_bytes()).hexdigest()
assert original_runner_sha=='fbe50aac9cc219f18633cd083535c7a16a4a2717f1c6bcdbdd36d595de4f3541'
assert tool_sha=='195162d3ddfc5da85496771b2bd80a0deaede30ea7e4419ee1c4a1997b7742de'
assert resources_sha=='606a00ccbdd2e2cc0f931f22ec3e7367d7e286a3e58eeff479d27a716db1af02'
port_guard=root/'tools/BenchPortGuard.java';port_guard_sha=hashlib.sha256(port_guard.read_bytes()).hexdigest()
(out/'BenchPortGuard.input.java').write_bytes(port_guard.read_bytes())
(out/'original-runner.input.py').write_bytes((root/'scripts/tools/run-bot-runtime-bench.py').read_bytes())
(out/'bench_owned_resources.input.py').write_bytes(resources_file.read_bytes())
compile_result=subprocess.run([str(args.java_bin/('javac'+suffix)),'-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-cp',classpath,'-d',str(classes),str(tool),str(port_guard)],capture_output=True,creationflags=flags)
(out/'compile.log').write_bytes(compile_result.stdout+compile_result.stderr)
if compile_result.returncode:
    (out/'runner-result.actual.json').write_text(json.dumps({'exit':compile_result.returncode,'stage':'compile','toolSha256':tool_sha,'processStarted':False}),encoding='utf8')
    raise SystemExit(compile_result.returncode)
env=os.environ.copy();env.update(SPRING_PROFILES_ACTIVE='dev',BOT_BENCH_OUT=str(out),BOT_BENCH_BUSINESS_NOW=str(args.business_now),BOT_BENCH_MONGO_TRANSPORT=args.mongo_transport)
env.pop('BOT_BENCH_DATABASE',None)
for name in ['IRONOATH_DEV_TIME_SPEED','IRONOATH_DEV_CITY_LEVEL','IRONOATH_DEV_START_AMOUNT']:env.pop(name,None)
command=[str(args.java_bin/('java'+suffix)),'-Xms512m','-Xmx2048m','-Dfile.encoding=UTF-8','-cp',os.pathsep.join([str(classes),classpath]),'BotRuntimeBench',
    '--server.port='+str(args.port),'--ironoath.storage='+('mongo' if args.storage=='mongo-redis' else 'memory'),'--ironoath.config-dir='+str(candidate/'config'),
    '--logging.level.root=WARN','--logging.level.com.ironoath=WARN']
run=uuid.uuid4().hex;database='ironoath_bot_bench_'+run;container='ironoath-bot-bench-'+run[:12];container_id=None
process=None;code=1;errors=[];start=time.time()
def docker(*values):
    response=subprocess.run(['docker',*values],capture_output=True,text=True,encoding='utf8',creationflags=flags,timeout=30)
    if response.returncode:raise RuntimeError('Docker operation failed: '+response.stderr[-1000:])
    return response.stdout.strip()
def mongo(script):
    assert database.startswith('ironoath_bot_bench_') and database.endswith(run)
    prefix='const d=db.getSiblingDB('+json.dumps(database)+');'
    return json.loads(docker('exec','mongo27','mongosh','--quiet','--eval',prefix+script).splitlines()[-1])
resources=OwnedResources(out,docker,mongo,{'run':run,'port':args.port,'redisPort':args.redis_port,
    'threadId':env.get('CODEX_THREAD_ID','standalone'),'jarSha256':sha,'businessNow':args.business_now,
    'toolSha256':tool_sha,'runnerSha256':runner_sha,'resourcesSha256':resources_sha,'hardware':platform.uname()._asdict(),
    'storage':args.storage,'database':database,'redisContainer':container})

java_image=docker('image','inspect',args.java_image,'--format','{{.Id}}');assert java_image.startswith('sha256:')
for key in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:
    assert not os.environ.get(key),'Inherited JVM options; values withheld'
thread=env.get('CODEX_THREAD_ID','standalone')
probe_folder=out/'portguard-owner';probe_folder.mkdir();app_folder=out/'java-owner';app_folder.mkdir()
probe=OwnedLinuxContainer(probe_folder,docker,run,thread,'ironoath-bot-probe-'+run[:12],java_image)
java_owner=OwnedLinuxContainer(app_folder,docker,run,thread,'ironoath-bot-java-'+run[:12],java_image)
bridge=None;mongo_before=None;java_state=None;native_cleanup={};control_status=[]

def container_controls():
    observations=[]
    for case in ['foreign-owner','renamed-known-id']:
        name='ironoath-bench-control-'+run[:12]+'-'+case
        folder=out/case;folder.mkdir()
        authority=OwnedLinuxContainer(folder,docker,run,thread,name,java_image)
        status={'name':name,'run':run,'threadId':thread,'image':java_image,'containerId':None,'removed':False}
        control_status.append(status)
        (out/'control-intents.actual.json').write_text(json.dumps(control_status,indent=2),encoding='utf8')
        identifier=None;row={'case':case,'guardRejected':False,'preservedBeforeControlledCleanup':False,'controlledContainerRemoved':False}
        try:
            identifier=docker('create','--pull=never','--name',name,'--label','ironoath.bench.owner='+('other-owner' if case=='foreign-owner' else run),
                '--label','codex.thread='+thread,'--label','ironoath.negative.control='+run,'--network','none','--entrypoint','java',java_image,'-version')
            status['containerId']=identifier
            (out/'control-intents.actual.json').write_text(json.dumps(control_status,indent=2),encoding='utf8')
            if case=='renamed-known-id':
                # Deliberately withhold the create reply from authority, then read back its exact ID.
                assert authority.reconcile()==identifier
                row['withheldCreationAckRecovered']=True
                docker('rename',identifier,name+'-renamed')
            try:authority.reconcile()
            except RuntimeError as error:
                expected='ownership changed' if case=='foreign-owner' else 'was renamed'
                if expected not in str(error):raise
                row['guardRejected']=True
            assert row['guardRejected'],'Invalid owner was not rejected'
            still=json.loads(docker('inspect',identifier))[0]
            row['preservedBeforeControlledCleanup']=still['Id']==identifier
        finally:
            candidates=[]
            if identifier:candidates.append(identifier)
            else:
                for alias in [name,name+'-renamed']:
                    found=docker('container','ls','-a','--filter','name=^/'+alias+'$','--format','{{.ID}}')
                    if found:candidates.append(found)
            for target in candidates:
                details=json.loads(docker('inspect',target))[0];labels=details['Config'].get('Labels') or {}
                # Separate control capability proves these deliberately foreign-labelled objects were created by this test.
                assert details['Image']==java_image and labels.get('ironoath.negative.control')==run and labels.get('codex.thread')==thread
                docker('rm','-f',details['Id'])
                assert not docker('container','ls','-a','--filter','id='+details['Id'],'--format','{{.ID}}')
            row['controlledContainerRemoved']=True
            status['removed']=True
            (out/'control-intents.actual.json').write_text(json.dumps(control_status,indent=2),encoding='utf8')
            observations.append(row)
            (out/'container-negative-controls.actual.json').write_text(json.dumps(observations,indent=2),encoding='utf8')
        assert all(row[key] for key in ['guardRejected','preservedBeforeControlledCleanup','controlledContainerRemoved'])
    return observations

def marker_control():
    token=uuid.uuid4().hex;name='ironoath_bot_bench_'+token
    assert name!=database
    folder=out/'mongo-marker-control';folder.mkdir()
    def control(script):
        assert name.startswith('ironoath_bot_bench_') and name.endswith(token)
        prefix='const d=db.getSiblingDB('+json.dumps(name)+');'
        return json.loads(docker('exec','mongo27','mongosh','--quiet','--eval',prefix+script).splitlines()[-1])
    authority=OwnedResources(folder,docker,control,{'run':token,'threadId':thread,'database':name,'redisContainer':'unused','redisPort':args.redis_port})
    row={'foreignMarkerRejected':False,'preservedBeforeControlledDrop':False,'controlNamespaceDropped':False}
    try:
        authority.attempting('mongo')
        control('if(d.getCollectionNames().length!==0)throw Error("Control database not fresh");d.getCollection("__owned_run").insertOne({_id:"deliberately-wrong-owner"});d.getCollection("__task_control").insertOne({_id:'+json.dumps(token)+'});print(JSON.stringify({created:true}));')
        rejected,failures=authority.cleanup(lambda:None,free)
        assert rejected=={'mongoDropped':False} and len(failures)==1 and failures[0]['stage']=='mongo-cleanup'
        row['foreignMarkerRejected']=True
        preserved=control('print(JSON.stringify({preserved:!!d.getCollection("__task_control").findOne({_id:'+json.dumps(token)+'})&&!!d.getCollection("__owned_run").findOne({_id:"deliberately-wrong-owner"})}));')
        row['preservedBeforeControlledDrop']=preserved['preserved'];assert preserved['preserved']
    finally:
        state=control('print(JSON.stringify({empty:d.getCollectionNames().length===0,control:!!d.getCollection("__task_control").findOne({_id:'+json.dumps(token)+'})}));')
        if state['control']:
            dropped=control('if(!d.getCollection("__task_control").findOne({_id:'+json.dumps(token)+'}))throw Error("Control capability mismatch");print(JSON.stringify(d.dropDatabase()));');assert dropped.get('ok')==1
        elif not state['empty']:
            raise RuntimeError('Control database ownership unknown; preserve it')
        absent=control('const a=db.getSiblingDB("admin").runCommand({listDatabases:1,filter:{name:d.getName()},nameOnly:true});print(JSON.stringify({absent:a.databases.length===0}));')
        row['controlNamespaceDropped']=absent['absent']
        (out/'mongo-marker-negative-control.actual.json').write_text(json.dumps(row,indent=2),encoding='utf8')
    assert all(row.values())

def persistence_readback():
    assert resources.container()==container_id
    fsync=docker('exec',container,'redis-cli','--raw','CONFIG','GET','appendfsync').splitlines()
    append=docker('exec',container,'redis-cli','--raw','CONFIG','GET','appendonly').splitlines()
    assert fsync==['appendfsync','always'] and append==['appendonly','yes']
    raw=docker('exec',container,'redis-cli','--raw','INFO','persistence')
    allowed={'aof_enabled','aof_current_size','aof_last_write_status','aof_last_bgrewrite_status'}
    info={line.split(':',1)[0]:line.split(':',1)[1].strip() for line in raw.splitlines() if ':' in line and line.split(':',1)[0] in allowed}
    assert info.get('aof_enabled')=='1' and info.get('aof_last_write_status')=='ok'
    return {'appendfsync':fsync,'appendonly':append,'info':info}

def create_java(authority,guest_args,guest_env):
    values=['create','--pull=never','--name',authority.intent['name'],'--label','ironoath.bench.owner='+run,
        '--label','codex.thread='+thread,'--network','bridge','--read-only','--cap-drop=ALL','--security-opt','no-new-privileges',
        '--user','65534:65534','--workdir','/out','--tmpfs','/tmp:rw,nosuid,size=128m','--entrypoint','java',
        '--mount','type=bind,source='+str(unpacked/'BOOT-INF')+',target=/baseline,readonly',
        '--mount','type=bind,source='+str(classes)+',target=/fixture,readonly',
        '--mount','type=bind,source='+str(candidate/'config')+',target=/config,readonly',
        '--mount','type=bind,source='+str(out)+',target=/out']
    for key,value in guest_env.items():values+=['--env',key+'='+value]
    # Name/labels were persisted before create; reconcile recovers a lost creation reply.
    authority.save();identifier=docker(*values,java_image,*guest_args)
    authority.acknowledge(identifier);assert authority.reconcile()==identifier
    return identifier

try:
    container_controls()
    probe_id=create_java(probe,['-cp','/fixture:/baseline/classes:/baseline/lib/*','BenchPortGuard','/out/portguard.actual.json',str(args.port)],{})
    with (out/'portguard.log').open('wb') as log:
        p=subprocess.run(['docker','start','--attach',probe_id],cwd=root,stdout=log,stderr=subprocess.STDOUT,creationflags=flags,timeout=60)
    assert p.returncode==0,'Actual Linux portguard/JRE preflight failed'
    guard=json.loads((out/'portguard.actual.json').read_text());assert all(guard[key] for key in ['targetFree','busyNegativeRejected','busyListenerPreserved','ephemeralReleased','protected9490RejectedWithoutBinding'])
    assert guard['osName']=='Linux' and guard['javaVersion'].startswith('17.')
    assert probe.remove();native_cleanup['portGuardContainerRemoved']=True
    if args.storage=='mongo-redis':
        mongo_before=json.loads(docker('inspect','mongo27'))[0];assert mongo_before['State']['Running']
        marker_control()
        resources.attempting('mongo')
        mongo('if(d.getCollectionNames().length!==0)throw Error("Database not fresh");d.getCollection("__owned_run").insertOne({_id:'+json.dumps(run)+'});print(JSON.stringify({owned:true}));')
        image=docker('image','inspect','redis:alpine','--format','{{.Id}}');assert image.startswith('sha256:')
        redis_data=out/'redis-data';redis_data.mkdir();resources.attempting('redis')
        container_id=docker('run','-d','--pull=never','--name',container,'--label','ironoath.bench.owner='+run,'--label','codex.thread='+thread,
            '-p','127.0.0.1:'+str(args.redis_port)+':6379','-v',str(redis_data)+':/data',image,'redis-server','--appendonly','yes','--appendfsync','always')
        resources.acknowledge(redisContainerId=container_id);resources.container();assert docker('exec',container,'redis-cli','--raw','PING')=='PONG'
        persistence=persistence_readback()
        (out/'redis-persistence-before.actual.json').write_text(json.dumps(persistence,indent=2),encoding='utf8')
        redis_details=json.loads(docker('inspect',container_id))[0];bridge=bridge_endpoints(mongo_before,redis_details)
        assert redis_details['HostConfig']['NetworkMode']=='default' or redis_details['HostConfig']['NetworkMode']=='bridge'
        env['BOT_BENCH_DATABASE']=database
        command+=['--spring.data.mongodb.uri=mongodb://'+bridge['mongoIp']+':27017/'+database,'--spring.data.mongodb.database='+database,
            '--ironoath.lock=redisson','--ironoath.bot.task-queue=redis','--ironoath.redis.address=redis://'+bridge['redisIp']+':6379']
        resources.acknowledge(linuxBridge=bridge,redisPersistenceReadback=persistence,redisDataBind=str(redis_data))
    guest=list(command[1:]);guest[guest.index('-cp')+1]='/fixture:/baseline/classes:/baseline/lib/*'
    guest=[('--ironoath.config-dir=/config' if value.startswith('--ironoath.config-dir=') else value) for value in guest]
    java_id=create_java(java_owner,guest,guest_environment(env))
    with (out/'backend.log').open('wb') as log:
        process=subprocess.Popen(['docker','start','--attach',java_id],cwd=root,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
        resources.acknowledge(hostDockerAttachPid=process.pid,hostDockerAttachPidIsJvm=False,javaContainerId=java_id,javaImageId=java_image)
        deadline=time.monotonic()+15
        while True:
            java_state=json.loads(docker('inspect',java_id))[0]
            if java_state['State']['Running'] or process.poll() is not None:break
            assert time.monotonic()<deadline,'Owned Java container startup timeout';time.sleep(.05)
        if java_state['State']['Running']:
            assert docker('exec',java_id,'cat','/proc/1/comm')=='java'
            resources.acknowledge(linuxVmHostPid=java_state['State']['Pid'],jvmPidInContainer=1)
            if bridge is not None:
                assert bridge['networkId'] in {n['NetworkID'] for n in java_state['NetworkSettings']['Networks'].values()},'Java not on existing common bridge'
        code=process.wait(timeout=900)
except Exception as error:
    errors.append({'type':type(error).__name__,'message':str(error)})
finally:
    # The host attach CLI is separate from the Java container authority. Its
    # failure must not bypass the ownership-checked cleanup or final receipt.
    host_attach_exited=process is None
    try:
        if process and process.poll() is None:process.terminate();process.wait(timeout=20)
    except Exception as error:
        code=1;errors.append({'stage':'host-docker-attach-cleanup','type':type(error).__name__,'message':str(error)})
    for key,authority in [('javaContainerRemoved',java_owner),('portGuardContainerRemoved',probe)]:
        try:native_cleanup[key]=authority.remove()
        except Exception as error:native_cleanup[key]=False;errors.append({'stage':key,'type':type(error).__name__,'message':str(error)})
    try:host_attach_exited=process is None or process.poll() is not None
    except Exception as error:
        code=1;errors.append({'stage':'host-docker-attach-cleanup','type':type(error).__name__,'message':str(error)})
    cleanup={**native_cleanup,'negativeControlContainersRemoved':all(item['removed'] for item in control_status),'processExited':native_cleanup.get('javaContainerRemoved',False),'hostDockerAttachExited':host_attach_exited,'portFree':free()}
    if args.storage=='mongo-redis' and resources.intent['redisAttempted']:
        try:
            if resources.container() is not None:
                persistence_after=persistence_readback()
                (out/'redis-persistence-after.actual.json').write_text(json.dumps(persistence_after,indent=2),encoding='utf8')
                cleanup['redisAofStillEnabledAndHealthy']=True
        except Exception as error:
            cleanup['redisAofStillEnabledAndHealthy']=False;errors.append({'stage':'redis-persistence-readback','type':type(error).__name__,'message':str(error)})
    def audit_mongo():
        audit=mongo('if(!d.getCollection("__owned_run").findOne({_id:'+json.dumps(run)+'}))throw Error("Owner mismatch");const counts={};for(const name of d.getCollectionNames())counts[name]=d.getCollection(name).countDocuments({});print(JSON.stringify({counts,players:d.getCollection("player").find({},{_id:1,power:1,lastBotCalibrationAt:1}).limit(2).toArray(),cities:d.getCollection("city").find({}).limit(2).toArray()}));')
        (out/'mongo-readback.actual.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf8')
    resource_cleanup,resource_errors=resources.cleanup(audit_mongo,free)
    cleanup.update(resource_cleanup);errors.extend(resource_errors)
    if args.storage=='mongo-redis' and cleanup.get('mongoDropped'):
        try:
            absent=mongo('const a=db.getSiblingDB("admin").runCommand({listDatabases:1,filter:{name:d.getName()},nameOnly:true});print(JSON.stringify({absent:a.databases.length===0}));');cleanup['mongoNamespaceAbsent']=absent['absent']
            current=json.loads(docker('inspect','mongo27'))[0];cleanup['originalMongoContainerUnchanged']=bool(mongo_before and current['Id']==mongo_before['Id'] and current['State']['Running'] and current['State']['StartedAt']==mongo_before['State']['StartedAt'])
        except Exception as error:cleanup['mongoNamespaceAbsent']=False;errors.append({'stage':'linux-final-owner-readback','type':type(error).__name__,'message':str(error)})
    if resource_errors:code=1
    if (not all(cleanup.values()) or hashlib.sha256(tool.read_bytes()).hexdigest()!=tool_sha
            or hashlib.sha256(resources_file.read_bytes()).hexdigest()!=resources_sha
            or hashlib.sha256(linux_owner_file.read_bytes()).hexdigest()!=linux_owner_sha
            or hashlib.sha256(original_runner_file.read_bytes()).hexdigest()!=original_runner_sha
            or hashlib.sha256(port_guard.read_bytes()).hexdigest()!=port_guard_sha
            or hashlib.sha256(Path(__file__).read_bytes()).hexdigest()!=runner_sha):code=1
    result={'exit':code,'errors':errors,'elapsedSeconds':time.time()-start,'cleanup':cleanup,'jarSha256':sha,
        'toolSha256':tool_sha,'runnerSha256':runner_sha,'resourcesSha256':resources_sha,'configHashes':config_hashes,'storage':args.storage,'mongoTransportExperiment':args.mongo_transport,'fixedBusinessTime':args.business_now,'devSpeedOverride':False,'runtimePlatform':'Linux Docker separate containers on existing bridge','originalFbeRunnerModified':False,'originalRunnerSha256':original_runner_sha,'portGuardSourceSha256':port_guard_sha,'linuxOwnerModuleSha256':linux_owner_sha,'javaImageId':java_image,'javaContainerId':java_owner.intent['containerId'],'linuxBridge':bridge,'compilePlatform':platform.system(),'processStarted':process is not None,'fixtureResultPresent':(out/'result.actual.json').exists(),'scope':'Independent Linux orchestrator: actual Host JDK17 compile and Linux JRE17 JavaPID1, not original fbe process. Same original195 fixture/count/rules/time/thresholds/services/Netty2. Storage readback is authoritative: mongo-redis uses real owned Mongo+Redisson/RedisAOFalways/D-drive bind; memory is InJvm regression only. Windows benchmark remains failed evidence.'}
    (out/'runner-result.actual.json').write_text(json.dumps(result,indent=2),encoding='utf8')
    print(json.dumps({k:v for k,v in result.items() if k!='configHashes'}),flush=True)
raise SystemExit(code)
