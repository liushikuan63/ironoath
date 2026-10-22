import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const bash=process.env.RUNTIME_STATUS_TEST_BASH??(process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');

for(const [batch,cleanup,expected] of [[0,0,0],[1,0,1],[2,0,2],[0,1,1]])
test(`parent forwards batch ${batch} and cleanup ${cleanup} as ${expected}`,()=>{
  fs.mkdirSync(path.join(root,'tmp'),{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'tmp','runtime-parent-test-'));
  try{
    const log=path.join(dir,'controlled parent.log');
    const result=spawnSync(bash,['scripts/run-batch-dual-backend.sh','--self-test-exit-propagation',String(batch),String(cleanup),log.replaceAll('\\','/')],{cwd:root,encoding:'utf8',timeout:10000});
    assert.ifError(result.error);assert.match(fs.readFileSync(log,'utf8'),new RegExp(`BATCH_EXIT=${batch}`));
    assert.equal(result.status,expected,result.stdout+result.stderr);
  }finally{
    assert.equal(path.dirname(path.resolve(dir)),path.resolve(root,'tmp'));
    assert.match(path.basename(dir),/^runtime-parent-test-/);fs.rmSync(dir,{recursive:true,force:true});
  }
});
