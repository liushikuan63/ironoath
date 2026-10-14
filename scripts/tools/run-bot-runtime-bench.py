"""Run the finite B11 benchmark against a frozen candidate; no installation or existing-account writes."""
from pathlib import Path
import argparse,hashlib,json,os,platform,socket,subprocess,time,zipfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--candidate',type=Path,required=True,help='Frozen directory: city-runtime.jar, config/, inputs.json')
parser.add_argument('--out',type=Path,required=True,help='New evidence directory; existing paths are preserved')
parser.add_argument('--java-bin',type=Path,required=True,help='Project JDK 17 bin directory')
parser.add_argument('--port',type=int,default=9885)
parser.add_argument('--business-now',type=int,required=True,help='Fixed positive business timestamp; performance budget remains real nanoTime')
args=parser.parse_args()
root=Path(__file__).resolve().parents[2];candidate=args.candidate.resolve();out=args.out.resolve()
assert 1024<=args.port<=65535 and args.business_now>0
jar=candidate/'city-runtime.jar';inputs=json.loads((candidate/'inputs.json').read_text(encoding='utf8'))
sha=hashlib.sha256(jar.read_bytes()).hexdigest();assert sha==inputs['jarSha256'],'Frozen JAR SHA mismatch'
config_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (candidate/'config').glob('*.json')}
assert config_hashes==inputs['configHashes'],'Frozen config SHA mismatch'
def free():
    with socket.socket() as sock:return sock.connect_ex(('127.0.0.1',args.port))!=0
assert free(),'Benchmark port belongs to an existing process; preserved'
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
compile_result=subprocess.run([str(args.java_bin/('javac'+suffix)),'-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-cp',classpath,'-d',str(classes),str(tool)],capture_output=True,creationflags=flags)
(out/'compile.log').write_bytes(compile_result.stdout+compile_result.stderr)
if compile_result.returncode:
    (out/'runner-result.actual.json').write_text(json.dumps({'exit':compile_result.returncode,'stage':'compile','toolSha256':tool_sha,'processStarted':False}),encoding='utf8')
    raise SystemExit(compile_result.returncode)
env=os.environ.copy();env.update(SPRING_PROFILES_ACTIVE='dev',BOT_BENCH_OUT=str(out),BOT_BENCH_BUSINESS_NOW=str(args.business_now))
for name in ['IRONOATH_DEV_TIME_SPEED','IRONOATH_DEV_CITY_LEVEL','IRONOATH_DEV_START_AMOUNT']:env.pop(name,None)
command=[str(args.java_bin/('java'+suffix)),'-Xms512m','-Xmx2048m','-Dfile.encoding=UTF-8','-cp',os.pathsep.join([str(classes),classpath]),'BotRuntimeBench',
    '--server.port='+str(args.port),'--ironoath.storage=memory','--ironoath.config-dir='+str(candidate/'config'),
    '--logging.level.root=WARN','--logging.level.com.ironoath=WARN']
process=None;code=1;errors=[];start=time.time()
try:
    with (out/'backend.log').open('wb') as log:
        process=subprocess.Popen(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
        (out/'owner.actual.json').write_text(json.dumps({'pid':process.pid,'port':args.port,'threadId':env.get('CODEX_THREAD_ID'),
            'jarSha256':sha,'businessNow':args.business_now,'toolSha256':tool_sha,'hardware':platform.uname()._asdict()}),encoding='utf8')
        code=process.wait(timeout=900)
except Exception as error:
    errors.append({'type':type(error).__name__,'message':str(error)})
finally:
    if process and process.poll() is None:process.terminate();process.wait(timeout=20)
    cleanup={'processExited':process is None or process.poll() is not None,'portFree':free()}
    if not all(cleanup.values()) or hashlib.sha256(tool.read_bytes()).hexdigest()!=tool_sha:code=1
    result={'exit':code,'errors':errors,'elapsedSeconds':time.time()-start,'cleanup':cleanup,'jarSha256':sha,
        'toolSha256':tool_sha,'configHashes':config_hashes,'fixedBusinessTime':args.business_now,'devSpeedOverride':False}
    (out/'runner-result.actual.json').write_text(json.dumps(result,indent=2),encoding='utf8')
    print(json.dumps({k:v for k,v in result.items() if k!='configHashes'}),flush=True)
raise SystemExit(code)
