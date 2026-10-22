import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const bash=process.env.RUNTIME_STATUS_TEST_BASH??(process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
const nativeArg=p=>p.replaceAll('\\','/');
function fixture(run){
  fs.mkdirSync(path.join(root,'tmp'),{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'tmp','runtime-status-test-'));
  try{return run(dir);}finally{
    assert.equal(path.dirname(path.resolve(dir)),path.resolve(root,'tmp'));
    assert.match(path.basename(dir),/^runtime-status-test-/);
    fs.rmSync(dir,{recursive:true,force:true});
  }
}
function shell(args,env={}){
  const result=spawnSync(bash,['scripts/run-runtime-probes.sh',...args.map(nativeArg)],{
    cwd:root,encoding:'utf8',timeout:45000,env:{...process.env,OPS_TOKEN_VALUE:'',...env}});
  assert.ifError(result.error);return result;
}
for(const [name,ledger,exit] of [
  ['all success with metadata','# backend\n0 first.mjs\n# RERUN note\n0 second.mjs\n',0],
  ['timeout cannot be green','0 first.mjs\nTIMEOUT second.mjs\n',2],
  ['prerequisite cannot be green','PREREQ only.mjs\n',2],
  ['missing runner cannot be green','NO-RUN only.mjs\n',2],
  ['skip is unverified','0 first.mjs\nSKIP other.mjs\n',2],
  ['failure takes precedence over incomplete','TIMEOUT a.mjs\n1 b.mjs\n',1],
  ['empty ledger fails closed','# metadata only\n',2],
  ['unknown status fails closed','SURPRISE a.mjs\n',2],
  ['CRLF and final non-newline row','0 a.mjs\r\nPREREQ b.mjs',2],
])test(`status ledger: ${name}`,()=>fixture(dir=>{
  const ledgerPath=path.join(dir,'ledger with spaces.txt');fs.writeFileSync(ledgerPath,ledger);
  const result=shell(['--summarize-status',ledgerPath]);assert.equal(result.status,exit,result.stdout+result.stderr);
}));

for(const [name,source,expectedStatus,expectedLedger] of [
  ['success',"console.log('fixture passed');",0,/^0 /m],
  ['declared prerequisite',"console.log('fixture prerequisite missing');process.exitCode=2;",2,/^PREREQ /m],
  ['module cannot start',"import './deliberately-missing-owned-module.mjs';",2,/^NO-RUN /m],
  ['timeout',"console.log('owned timeout fixture started');setInterval(()=>{},1000);",2,/^TIMEOUT /m],
  ['retry prerequisite',"import fs from 'node:fs';const p=new URL('./retry.marker',import.meta.url);const retry=fs.existsSync(p);fs.writeFileSync(p,'owned');console.log(retry?'retry prerequisite missing':'first assertion failed');process.exitCode=retry?2:1;",2,/^PREREQ /m],
  ['explicit skip',"throw Error('this selected skip must not execute');",2,/^SKIP /m],
])test(`actual controlled probe: ${name}`,()=>fixture(dir=>{
  const probe=path.join(dir,name==='explicit skip'?'verify-label-fit-runtime.mjs':'probe.mjs'),list=path.join(dir,'list.txt'),out=path.join(dir,'statuses.txt');
  fs.writeFileSync(probe,source);fs.writeFileSync(list,nativeArg(probe)+'\n');
  const result=shell([list],{BACKEND:'http://127.0.0.1:1',RUNTIME_PROBES_OUT:nativeArg(out),
    RUNTIME_PROBES_LOGDIR:nativeArg(path.join(dir,'logs')),RUNTIME_PROBES_TIMEOUT:name==='timeout'?'1':'15'});
  assert.match(fs.readFileSync(out,'utf8'),expectedLedger);
  assert.equal(result.status,expectedStatus,result.stdout+result.stderr);
}));

test('selected final probe without newline is executed rather than silently omitted',()=>fixture(dir=>{
  const first=path.join(dir,'first.mjs'),second=path.join(dir,'second.mjs');
  const list=path.join(dir,'no-final-newline.list'),out=path.join(dir,'statuses.txt');
  fs.writeFileSync(first,"console.log('first passed');");
  fs.writeFileSync(second,"console.log('last prerequisite missing');process.exitCode=2;");
  fs.writeFileSync(list,nativeArg(first)+'\n'+nativeArg(second));
  const result=shell([list],{BACKEND:'http://127.0.0.1:1',RUNTIME_PROBES_OUT:nativeArg(out),
    RUNTIME_PROBES_LOGDIR:nativeArg(path.join(dir,'logs')),RUNTIME_PROBES_TIMEOUT:'15'});
  assert.match(fs.readFileSync(out,'utf8'),/^PREREQ .*second\.mjs /m);
  assert.equal(result.status,2,result.stdout+result.stderr);
}));
