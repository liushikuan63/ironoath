import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { retainResponseWait } from './runtime-response-wait.mjs';
test('a response retains its value and original waiter identity', async () => {
  const pending = Promise.resolve({ code: 0 });
  assert.equal(retainResponseWait(pending), pending);
  assert.deepEqual(await pending, { code: 0 });
});
test('an awaited browser-close failure retains its original error', async () => {
  const error = Error('Browser closed after click failure');
  const pending = retainResponseWait(Promise.reject(error));
  await assert.rejects(pending, actual => actual === error);
});
test('click failure before awaiting a response cannot terminate strict Node cleanup', () => {
  const module = new URL('./runtime-response-wait.mjs', import.meta.url).href;
  const code = `import {retainResponseWait} from ${JSON.stringify(module)};
    let close;retainResponseWait(new Promise((resolve,reject)=>{close=()=>reject(Error('browser closed'));}));
    try{throw Error('actual click failed');}catch(error){if(error.message!=='actual click failed')throw error;}
    finally{close();await new Promise(resolve=>setTimeout(resolve,10));process.stdout.write('original-failure-and-cleanup-persisted');}`;
  const result = spawnSync(process.execPath, ['--unhandled-rejections=strict', '--input-type=module', '-e', code], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, 'original-failure-and-cleanup-persisted');
});
