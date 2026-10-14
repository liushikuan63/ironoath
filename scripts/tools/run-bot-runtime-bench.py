"""Run the finite B11 benchmark against a frozen candidate; no installation or existing-account writes."""
from pathlib import Path
import argparse,hashlib,json,os,platform,socket,subprocess,time,uuid,zipfile
from bench_owned_resources import OwnedResources

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--candidate',type=Path,required=True,help='Frozen directory: city-runtime.jar, config/, inputs.json')
parser.add_argument('--out',type=Path,required=True,help='New evidence directory; existing paths are preserved')
parser.add_argument('--java-bin',type=Path,required=True,help='Project JDK 17 bin directory')
parser.add_argument('--port',type=int,default=9885)
parser.add_argument('--business-now',type=int,required=True,help='Fixed positive business timestamp; performance budget remains real nanoTime')
parser.add_argument('--storage',choices=['memory','mongo-redis'],default='memory')
parser.add_argument('--redis-port',type=int,default=6381,help='New owned Redis; existing owner is never stopped')
parser.add_argument('--mongo-transport',choices=['socket','netty'],default='socket',help='Explicit transport experiment; default stays unchanged')
args=parser.parse_args()
root=Path(__file__).resolve().parents[2];candidate=args.candidate.resolve();out=args.out.resolve()
assert 1024<=args.port<=65535 and args.business_now>0
jar=candidate/'city-runtime.jar';inputs=json.loads((candidate/'inputs.json').read_text(encoding='utf8'))
sha=hashlib.sha256(jar.read_bytes()).hexdigest();assert sha==inputs['jarSha256'],'Frozen JAR SHA mismatch'
config_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (candidate/'config').glob('*.json')}
assert config_hashes==inputs['configHashes'],'Frozen config SHA mismatch'
def free(port=None):
    with socket.socket() as sock:return sock.connect_ex(('127.0.0.1',args.port if port is None else port))!=0
assert free(),'Benchmark port belongs to an existing process; preserved'
assert 1024<=args.redis_port<=65535 and args.redis_port!=args.port
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
(out/'bench_owned_resources.input.py').write_bytes(resources_file.read_bytes())
compile_result=subprocess.run([str(args.java_bin/('javac'+suffix)),'-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-cp',classpath,'-d',str(classes),str(tool)],capture_output=True,creationflags=flags)
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
try:
    if args.storage=='mongo-redis':
        resources.attempting('mongo')
        mongo('if(d.getCollectionNames().length!==0)throw Error("Database not fresh");d.getCollection("__owned_run").insertOne({_id:'+json.dumps(run)+'});print(JSON.stringify({owned:true}));')
        image=docker('image','inspect','redis:alpine','--format','{{.Id}}');assert image.startswith('sha256:')
        redis_data=out/'redis-data';redis_data.mkdir()
        resources.attempting('redis')
        container_id=docker('run','-d','--name',container,'--label','ironoath.bench.owner='+run,'--label','codex.thread='+env.get('CODEX_THREAD_ID','standalone'),
            '-p','127.0.0.1:'+str(args.redis_port)+':6379','-v',str(redis_data)+':/data',image,'redis-server','--appendonly','yes','--appendfsync','always')
        resources.acknowledge(redisContainerId=container_id)
        resources.container();assert docker('exec',container,'redis-cli','--raw','PING')=='PONG'
        env['BOT_BENCH_DATABASE']=database
        command+=['--spring.data.mongodb.uri=mongodb://127.0.0.1:27017/'+database,'--spring.data.mongodb.database='+database,
            '--ironoath.lock=redisson','--ironoath.bot.task-queue=redis','--ironoath.redis.address=redis://127.0.0.1:'+str(args.redis_port)]
    with (out/'backend.log').open('wb') as log:
        process=subprocess.Popen(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
        resources.acknowledge(pid=process.pid)
        code=process.wait(timeout=900)
except Exception as error:
    errors.append({'type':type(error).__name__,'message':str(error)})
finally:
    if process and process.poll() is None:process.terminate();process.wait(timeout=20)
    cleanup={'processExited':process is None or process.poll() is not None,'portFree':free()}
    def audit_mongo():
        audit=mongo('if(!d.getCollection("__owned_run").findOne({_id:'+json.dumps(run)+'}))throw Error("Owner mismatch");const counts={};for(const name of d.getCollectionNames())counts[name]=d.getCollection(name).countDocuments({});print(JSON.stringify({counts,players:d.getCollection("player").find({},{_id:1,power:1,lastBotCalibrationAt:1}).limit(2).toArray(),cities:d.getCollection("city").find({}).limit(2).toArray()}));')
        (out/'mongo-readback.actual.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf8')
    resource_cleanup,resource_errors=resources.cleanup(audit_mongo,free)
    cleanup.update(resource_cleanup);errors.extend(resource_errors)
    if resource_errors:code=1
    if (not all(cleanup.values()) or hashlib.sha256(tool.read_bytes()).hexdigest()!=tool_sha
            or hashlib.sha256(resources_file.read_bytes()).hexdigest()!=resources_sha
            or hashlib.sha256(Path(__file__).read_bytes()).hexdigest()!=runner_sha):code=1
    result={'exit':code,'errors':errors,'elapsedSeconds':time.time()-start,'cleanup':cleanup,'jarSha256':sha,
        'toolSha256':tool_sha,'runnerSha256':runner_sha,'resourcesSha256':resources_sha,'configHashes':config_hashes,'storage':args.storage,'mongoTransportExperiment':args.mongo_transport,'fixedBusinessTime':args.business_now,'devSpeedOverride':False}
    (out/'runner-result.actual.json').write_text(json.dumps(result,indent=2),encoding='utf8')
    print(json.dumps({k:v for k,v in result.items() if k!='configHashes'}),flush=True)
raise SystemExit(code)
