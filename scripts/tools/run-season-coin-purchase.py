"""B24 finite real-UI acceptance: owned memory1x/newUID, configured season anchor, no real payment."""
from pathlib import Path
import argparse,datetime,hashlib,json,os,socket,subprocess,time,urllib.request,urllib.error,uuid

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--candidate',type=Path,required=True,help='Frozen city-runtime.jar/config/inputs.json, without a season deployment anchor')
parser.add_argument('--out',type=Path,required=True,help='New evidence directory; existing paths rejected')
parser.add_argument('--java-bin',type=Path,required=True,help='Project Java17 bin directory')
parser.add_argument('--node',type=Path,required=True,help='Project Node20 executable')
parser.add_argument('--port',type=int,default=9845)
parser.add_argument('--preview-port',type=int,default=9846)
args=parser.parse_args();root=Path(__file__).resolve().parents[2];source=args.candidate.resolve();out=args.out.resolve()
assert os.environ.get('CODEX_THREAD_ID'),'Run inside the current Codex task with its actual thread identity'
assert 1024<=args.port<=65535 and 1024<=args.preview_port<=65535 and args.port!=args.preview_port
def free(port):
 with socket.socket() as connection:return connection.connect_ex(('127.0.0.1',port))!=0
assert all(free(port) for port in [args.port,args.preview_port]),'Existing port owner preserved'
base=json.loads((source/'inputs.json').read_text(encoding='utf8'))
assert hashlib.sha256((source/'city-runtime.jar').read_bytes()).hexdigest()==base['jarSha256'],'Frozen JAR drift'
assert {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (source/'config').glob('*.json')}==base['configHashes'],'Frozen config drift'
table=json.loads((source/'config/global.json').read_text(encoding='utf8'))
assert not any(row['id']=='SEASON_START_AT' for row in table['rows']),'Existing deployment anchor preserved; choose unanchored frozen input'
rows=json.loads((source/'config/season.json').read_text(encoding='utf8'))['rows']
first_id=min(row['id'].rsplit('_phase_',1)[0] for row in rows)
phase=next(row for row in rows if row['id'].rsplit('_phase_',1)[0]==first_id and row['rulePhase']=='SETTLE')
zone=datetime.timezone(datetime.timedelta(hours=8));today=datetime.datetime.now(zone).replace(hour=0,minute=0,second=0,microsecond=0)
anchor=int((today-datetime.timedelta(days=phase['startDayOffset'])).timestamp()*1000)
out.mkdir(parents=True,exist_ok=False);scenario=out/'scenario';scenario.mkdir()
import shutil
shutil.copy2(source/'city-runtime.jar',scenario/'city-runtime.jar');shutil.copytree(source/'config',scenario/'config')
table['rows'].append({'id':'SEASON_START_AT','valueType':'LONG','value':anchor,'unit':'毫秒时间戳','source':'B14部署参数/独立验收夹具','why':'本次新实例按配置进入结算期，普通1x、不改奖励或价格'})
(scenario/'config/global.json').write_text(json.dumps(table,ensure_ascii=False,indent=2),encoding='utf8')
config_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (scenario/'config').glob('*.json')}
override={'id':'SEASON_START_AT','value':anchor,'derivedFrom':phase['id'],'offsetDays':phase['startDayOffset'],'expectedPhase':'SETTLE'}
(scenario/'inputs.json').write_text(json.dumps({'jarSha256':base['jarSha256'],'configHashes':config_hashes,'baseConfigHashes':base['configHashes'],'fixtureOverrides':[override]},indent=2),encoding='utf8')
tool=root/'tools/verify-season-coin-purchase-runtime.mjs';tool_sha=hashlib.sha256(tool.read_bytes()).hexdigest();(out/'probe.input.mjs').write_bytes(tool.read_bytes())
runner_sha=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
dependency_paths=['tools/lib/preview-server.mjs','tools/lib/guide-overlay.mjs','tools/lib/runtime-evidence-redaction.mjs','tools/lib/purchase-evidence.mjs','tools/lib/cocos-click.mjs']
dependency_hashes={name:hashlib.sha256((root/name).read_bytes()).hexdigest() for name in dependency_paths}
for name in dependency_paths:(out/(Path(name).name+'.input')).write_bytes((root/name).read_bytes())
windows=os.name=='nt';flags=subprocess.CREATE_NO_WINDOW if windows else 0;suffix='.exe' if windows else ''
env=os.environ.copy();token=uuid.uuid4().hex
env.update(SPRING_PROFILES_ACTIVE='dev',SEASON_SHOP_BACKEND='http://127.0.0.1:'+str(args.port),SEASON_SHOP_OPS_TOKEN=token,
 SEASON_SHOP_PORT=str(args.preview_port),SEASON_SHOP_OUT=str(out),SEASON_SHOP_OWNER=str(out/'owner.actual.json'))
for key in ['IRONOATH_DEV_TIME_SPEED','IRONOATH_DEV_CITY_LEVEL','IRONOATH_DEV_START_AMOUNT']:env.pop(key,None)
owner={'pid':None,'port':args.port,'previewPort':args.preview_port,'threadId':env['CODEX_THREAD_ID'],'jarSha256':base['jarSha256'],
 'configHashes':config_hashes,'fixtureOverrides':[override],'storage':'memory','timeSpeed':1,'newUidOnly':True,'toolSha256':tool_sha,'runnerSha256':runner_sha,'dependencyHashes':dependency_hashes}
(out/'owner.actual.json').write_text(json.dumps(owner,indent=2),encoding='utf8')
backend=probe=None;logs=[];exit_code=1;errors=[]
try:
 log=(out/'backend.log').open('wb');logs.append(log)
 backend=subprocess.Popen([str(args.java_bin/('java'+suffix)),'-Dfile.encoding=UTF-8','-jar',str(scenario/'city-runtime.jar'),
  '--server.port='+str(args.port),'--server.address=127.0.0.1','--ironoath.storage=memory','--ironoath.config-dir='+str(scenario/'config'),
  '--ironoath.ops.token='+token],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
 owner['pid']=backend.pid;(out/'owner.actual.json').write_text(json.dumps(owner,indent=2),encoding='utf8')
 deadline=time.monotonic()+90
 while True:
  assert backend.poll() is None,'Owned backend exited; see backend.log'
  try:
   with urllib.request.urlopen(env['SEASON_SHOP_BACKEND']+'/',timeout=2):break
  except urllib.error.HTTPError as error:
   if error.code==404:break
   raise
  except (urllib.error.URLError,TimeoutError):pass
  assert time.monotonic()<deadline,'Owned startup deadline exceeded';time.sleep(.2)
 log=(out/'probe.log').open('wb');logs.append(log)
 probe=subprocess.Popen([str(args.node),str(tool)],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
 exit_code=probe.wait(timeout=180)
except Exception as error:errors.append({'type':type(error).__name__,'message':str(error)})
finally:
 for process in [probe,backend]:
  if process and process.poll() is None:
   process.terminate()
   try:process.wait(timeout=20)
   except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
 for log in logs:log.close()
 cleanup={'backendExited':backend is None or backend.poll() is not None,'probeExited':probe is None or probe.poll() is not None,
  'portsFree':{str(port):free(port) for port in [args.port,args.preview_port]}}
 result_path=out/'result.actual.json'
 browser_state={}
 try:
  if result_path.exists():browser_state=json.loads(result_path.read_text(encoding='utf8')).get('cleanup',{})
 except (OSError,ValueError) as error:
  errors.append({'type':type(error).__name__,'message':str(error),'stage':'probe-evidence-readback'})
 cleanup['browserCloseVerified']=browser_state.get('browserClosed') is True
 cleanup['previewCloseVerified']=browser_state.get('previewClosed') is True
 drift=(hashlib.sha256(tool.read_bytes()).hexdigest()!=tool_sha or hashlib.sha256(Path(__file__).read_bytes()).hexdigest()!=runner_sha
  or any(hashlib.sha256((root/name).read_bytes()).hexdigest()!=value for name,value in dependency_hashes.items()))
 if errors or drift or not all(value if isinstance(value,bool) else all(value.values()) for value in cleanup.values()):exit_code=1
 result={'exit':exit_code,'probeExit':probe.poll() if probe is not None else None,'errors':errors,'cleanup':cleanup,'jarSha256':base['jarSha256'],'configHashes':config_hashes,'fixtureOverrides':[override],
  'toolSha256':tool_sha,'runnerSha256':runner_sha,'dependencyHashes':dependency_hashes,'sourceShaDrift':int(drift),'ordinaryTimeSpeed':1,'originalAccountChanged':False,'realPayment':False}
 (out/'runner-result.actual.json').write_text(json.dumps(result,indent=2),encoding='utf8');print(json.dumps({k:v for k,v in result.items() if k!='configHashes'}),flush=True)
raise SystemExit(exit_code)
