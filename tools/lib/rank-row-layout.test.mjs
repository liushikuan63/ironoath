import test from 'node:test';
import assert from 'node:assert/strict';
import { assertRowHorizontalBounds } from './rank-board-evidence.mjs';

const panel=()=>({bounds:{left:0,right:600},rows:[{cells:[{left:12,right:350,text:'第 1 名 长名称'},{left:370,right:588,text:'1,234,567'}]}]});
test('name and value boxes stay within the panel and separate columns',()=>{
  assert.deepEqual(assertRowHorizontalBounds(panel()),{rowCount:1,cellCount:2});
});
test('left and right overflow both fail even if text centers remain inside',()=>{
  const p=panel();p.rows[0].cells[0].left=-40;assert.throws(()=>assertRowHorizontalBounds(p),/横向/);
  const q=panel();q.rows[0].cells[1].right=640;assert.throws(()=>assertRowHorizontalBounds(q),/横向/);
});
test('overlap fails even when both boxes separately fit the outer panel',()=>{
  const p=panel();p.rows[0].cells[0].right=400;assert.throws(()=>assertRowHorizontalBounds(p),/重叠/);
});
test('missing panel or cell geometry and non-finite or inverted coordinates fail closed',()=>{
  for(const p of [null,{rows:[]},{bounds:{left:0,right:600},rows:[{}]},
    {bounds:{left:0,right:600},rows:[{cells:[{left:NaN,right:50}]}]},
    {bounds:{left:0,right:600},rows:[{cells:[{left:60,right:50}]}]}])
    assert.throws(()=>assertRowHorizontalBounds(p));
});
test('only subpixel rounding is tolerated at the edge',()=>{
  const p=panel();p.rows[0].cells[0].left=-0.2;assert.equal(assertRowHorizontalBounds(p).cellCount,2);
  p.rows[0].cells[0].left=-0.6;assert.throws(()=>assertRowHorizontalBounds(p),/横向/);
});
test('an actual empty board still supplies panel bounds and has no row cells',()=>{
  assert.deepEqual(assertRowHorizontalBounds({bounds:{left:0,right:600},rows:[]}),{rowCount:0,cellCount:0});
});
