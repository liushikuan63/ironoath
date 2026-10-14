"""B25 actual visible NPC loss, insufficient repeat and existing backend target refill acceptance on an owned new memory UID and explicit dev fixtures."""
from pathlib import Path
import argparse,hashlib,json,os,socket,subprocess,time,urllib.request,urllib.error,uuid

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--candidate',type=Path,required=True)
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--java-bin',type=Path,required=True)
parser.add_argument('--node',type=Path,required=True)
parser.add_argument('--port',type=int,default=9877)
parser.add_argument('--preview-port',type=int,default=9878)
parser.add_argument('--tool',type=Path,default=Path('tools/verify-loss-refill-runtime.mjs'))
args=parser.parse_args();root=Path(__file__).resolve().parents[2];candidate=args.candidate.resolve();out=args.out.resolve()
assert os.environ.get('CODEX_THREAD_ID'),'Actual current task identity required'
assert 1024<=args.port<=65535 and 1024<=args.preview_port<=65535 and args.port!=args.preview_port
suffix='.exe' if os.name=='nt' else ''
assert (args.java_bin/('java'+suffix)).is_file() and args.node.is_file()
base=json.loads((candidate/'inputs.json').read_text(encoding='utf8'));jar=candidate/'city-runtime.jar'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(jar)==base['jarSha256']
config_hashes={p.name:sha(p) for p in (candidate/'config').glob('*.json')}
assert config_hashes==base['configHashes'],'Complete frozen configuration differs'
def free(port):
 with socket.socket() as connection:return connection.connect_ex(('127.0.0.1',port))!=0
assert all(free(port) for port in [args.port,args.preview_port]),'Existing listeners preserved'
assert not out.exists(),'Use a new evidence directory'
web=root/'client/build/web-mobile';assert (web/'index.html').is_file(),'Build Web before running'
web_hashes={p.relative_to(web).as_posix():sha(p) for p in web.rglob('*') if p.is_file()}
tool=(root/args.tool).resolve();assert tool.is_relative_to(root/'tools') and tool.is_file();tool_sha=sha(tool);runner_sha=sha(Path(__file__))
dependencies=['tools/lib/march-evidence.mjs','tools/lib/resource-debit-evidence.mjs','tools/lib/runtime-response-wait.mjs','tools/lib/cocos-click.mjs','tools/lib/preview-server.mjs','tools/lib/guide-overlay.mjs','tools/lib/runtime-evidence-redaction.mjs','tools/lib/auto-refill-confirmation-evidence.mjs']
dependency_hashes={name:sha(root/name) for name in dependencies}
out.mkdir(parents=True);(out/'web-inputs.json').write_text(json.dumps(web_hashes,indent=2),encoding='utf8')
token=uuid.uuid4().hex;flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
env=os.environ.copy();env.update(SPRING_PROFILES_ACTIVE='dev',IRONOATH_DEV_CITY_LEVEL='16',IRONOATH_DEV_START_AMOUNT='5000',IRONOATH_DEV_TIME_SPEED='100',
 REPEAT_BACKEND='http://127.0.0.1:'+str(args.port),REPEAT_OPS_TOKEN=token,REPEAT_OUT=str(out),REPEAT_CONFIG=str(candidate/'config'),REPEAT_OWNER=str(out/'owner.actual.json'),REPEAT_PORT=str(args.preview_port))
for key in ['REPEAT_DIAG_ONLY','REPEAT_OFFLINE_DELAY','REPEAT_ROOT']:env.pop(key,None)
owner={'pid':None,'port':args.port,'previewPort':args.preview_port,'threadId':env['CODEX_THREAD_ID'],'jarSha256':base['jarSha256'],'configHashes':config_hashes,
 'cityLevel':16,'startAmount':5000,'timeSpeed':100,'storage':'memory','newUidOnly':True,'toolSha256':tool_sha,'runnerSha256':runner_sha,'dependencyHashes':dependency_hashes}
(out/'owner.actual.json').write_text(json.dumps(owner,indent=2),encoding='utf8')
backend=probe=None;logs=[];exit_code=1;errors=[]
try:
 log=(out/'backend.log').open('wb');logs.append(log)
 backend=subprocess.Popen([str(args.java_bin/('java'+suffix)),'-Dfile.encoding=UTF-8','-jar',str(jar),'--server.port='+str(args.port),'--server.address=127.0.0.1',
  '--ironoath.storage=memory','--ironoath.config-dir='+str(candidate/'config'),'--ironoath.ops.token='+token],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
 owner['pid']=backend.pid;(out/'owner.actual.json').write_text(json.dumps(owner,indent=2),encoding='utf8');deadline=time.monotonic()+90
 while True:
  assert backend.poll() is None,'Owned backend exited'
  try:
   with urllib.request.urlopen(env['REPEAT_BACKEND']+'/',timeout=2):break
  except urllib.error.HTTPError as error:
   if error.code==404:break
   raise
  except (urllib.error.URLError,TimeoutError):pass
  assert time.monotonic()<deadline,'Owned startup deadline exceeded';time.sleep(.2)
 log=(out/'probe.log').open('wb');logs.append(log)
 probe=subprocess.Popen([str(args.node),str(tool)],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=flags);exit_code=probe.wait(timeout=240)
except Exception as error:errors.append({'type':type(error).__name__,'message':str(error)})
finally:
 for process in [probe,backend]:
  if process and process.poll() is None:
   process.terminate()
   try:process.wait(timeout=20)
   except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
 for log in logs:log.close()
 cleanup={'backendExited':backend is None or backend.poll() is not None,'probeExited':probe is None or probe.poll() is not None,'portsFree':{str(port):free(port) for port in [args.port,args.preview_port]}}
 try:
  state=json.loads((out/'result.actual.json').read_text(encoding='utf8'))
  cleanup.update(browserCloseVerified=state.get('cleanup',{}).get('browserClosed') is True,previewCloseVerified=state.get('cleanup',{}).get('previewClosed') is True)
 except (OSError,ValueError) as error:
  errors.append({'type':type(error).__name__,'stage':'probe-evidence-readback','message':str(error)})
 drift=sha(tool)!=tool_sha or sha(Path(__file__))!=runner_sha or any(sha(root/name)!=value for name,value in dependency_hashes.items())
 drift=drift or {p.name:sha(p) for p in (candidate/'config').glob('*.json')}!=config_hashes or sha(jar)!=base['jarSha256']
 drift=drift or {p.relative_to(web).as_posix():sha(p) for p in web.rglob('*') if p.is_file()}!=web_hashes
 if errors or drift or not all(value if isinstance(value,bool) else all(value.values()) for value in cleanup.values()):exit_code=1
 result={'exit':exit_code,'probeExit':probe.poll() if probe else None,'errors':errors,'cleanup':cleanup,'jarSha256':base['jarSha256'],'configHashes':config_hashes,
  'toolSha256':tool_sha,'runnerSha256':runner_sha,'dependencyHashes':dependency_hashes,'sourceShaDrift':int(drift),'webFiles':len(web_hashes),
  'fixture':{'cityLevel':16,'startAmount':5000,'timeSpeed':100,'hero':'configured HERO via owned ops mail, actual claim and lineup; no troops mocked'},'originalAccountChanged':False,'realPayment':False}
 (out/'runner-result.actual.json').write_text(json.dumps(result,indent=2),encoding='utf8');print(json.dumps({k:v for k,v in result.items() if k!='configHashes'}),flush=True)
raise SystemExit(exit_code)
