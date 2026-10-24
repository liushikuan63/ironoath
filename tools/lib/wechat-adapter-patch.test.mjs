import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const patcher=path.join(root,'scripts/patch-wechat-adapter.mjs');

function withAdapter(source,check){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ironoath-wechat-adapter-'));
  const file=path.join(dir,'web-adapter.js');
  try{
    fs.writeFileSync(file,source,'utf8');
    return check(file);
  }finally{
    assert.equal(path.dirname(path.resolve(dir)),path.resolve(os.tmpdir()));
    assert.match(path.basename(dir),/^ironoath-wechat-adapter-/);
    fs.rmSync(dir,{recursive:true,force:true});
  }
}

const creator387Readable=`
var window = _interopRequireWildcard(require("./window"));
var _window = _interopRequireWildcard(require("./window"));
var global = GameGlobal;
var platform = "devtools";
if (typeof __devtoolssubcontext === 'undefined' && platform === 'devtools') {
  for (var key in _window) {
    var descriptor = Object.getOwnPropertyDescriptor(global, key);
    if (!descriptor || descriptor.configurable === true) {
      Object.defineProperty(window, key, { value: _window[key] });
    }
  }
  for (var _key2 in _window.document) {
    var _descriptor = Object.getOwnPropertyDescriptor(global.document, _key2);
    if (!_descriptor || _descriptor.configurable === true) {
      Object.defineProperty(global.document, _key2, { value: _window.document[_key2] });
    }
  }
  window.parent = window;
} else {
  for (var _key4 in _window) global[_key4] = _window[_key4];
  global.window = _window;
  window = global;
  window.top = window.parent = window;
}
window.fsUtils = module.exports = fsUtils;
`;

test('patches Cocos 3.8.7 readable adapter and is idempotent',()=>withAdapter(creator387Readable,file=>{
  const first=spawnSync(process.execPath,[patcher,file],{encoding:'utf8'});
  assert.ifError(first.error);assert.equal(first.status,0,first.stdout+first.stderr);
  const patched=fs.readFileSync(file,'utf8');
  assert.match(patched,/global\.window\s*=\s*global\.window\s*\|\|\s*_window/);
  assert.match(patched,/global\.document\s*=\s*global\.document\s*\|\|/);
  assert.doesNotMatch(patched,/Object\.getOwnPropertyDescriptor\(global\.document, _key2\)/);
  const second=spawnSync(process.execPath,[patcher,file],{encoding:'utf8'});
  assert.ifError(second.error);assert.equal(second.status,0,second.stdout+second.stderr);
  assert.equal(fs.readFileSync(file,'utf8'),patched);
}));

test('retains the legacy minified Cocos adapter anchor',()=>withAdapter(
  'var i=GameGlobal,r=window,o="devtools";if("undefined"==typeof __devtoolssubcontext&&"devtools"===o){old()}else{fallback()}',
  file=>{
    const result=spawnSync(process.execPath,[patcher,file],{encoding:'utf8'});
    assert.ifError(result.error);assert.equal(result.status,0,result.stdout+result.stderr);
    assert.match(fs.readFileSync(file,'utf8'),/i\.window\|\|\(i\.window=r\),i\.document\|\|\(i\.document=\{\}\)/);
  }
));

test('unknown adapter shape fails closed with a useful message',()=>withAdapter('var noAdapterAnchor=true;',file=>{
  const result=spawnSync(process.execPath,[patcher,file],{encoding:'utf8'});
  assert.ifError(result.error);assert.notEqual(result.status,0);
  assert.match(result.stderr,/devtools.*(?:anchor|锚点)/i);
}));
