import test from 'node:test';
import assert from 'node:assert/strict';
import { assertRankBoardEvidence, matchesRankBoardResponse } from './rank-board-evidence.mjs';

const entries=[{rank:1,id:'owned-one',name:'留守兵验收',value:249},{rank:2,id:'owned-two',name:'留守兵验收',value:173}];
const reply=(rows=entries,type='KILL')=>({status:200,body:{code:0,data:{type,entries:rows,page:1,pageSize:6,hasMore:false}}});
const panel=(rows=entries)=>({labels:rows.length?['击杀榜']:['击杀榜','这个榜还没有人'],
  rows:rows.map((e,index)=>({y:200-index*30,labels:[`第 ${e.rank} 名  ${e.name}`,String(e.value).replace(/\B(?=(\d{3})+(?!\d))/g,',')]}))});

test('response binding uses the configured backend rather than the preview host or another tab',()=>{
  const backend='http://127.0.0.1:8523';
  assert.equal(matchesRankBoardResponse(backend+'/rank/list?type=KILL&page=1','GET','KILL',backend),true);
  for(const [url,method] of [['http://localhost:8563/rank/list?type=KILL','GET'],
    [backend+'/rank/list?type=POWER','GET'],[backend+'/rank/list?type=KILL','POST'],
    [backend+'/rank/listing?type=KILL','GET'],['invalid','GET']])
    assert.equal(matchesRankBoardResponse(url,method,'KILL',backend),false);
});

test('authoritative empty board requires the visible empty explanation and no old rows',()=>{
  assert.deepEqual(assertRankBoardEvidence(reply([]),'KILL',panel([])),{kind:'empty',rowCount:0});
  assert.throws(()=>assertRankBoardEvidence(reply([]),'KILL',panel()),/空榜/);
});
test('nonempty board validates each response rank, name and value on the same visible row',()=>{
  assert.deepEqual(assertRankBoardEvidence(reply(),'KILL',panel()),{kind:'populated',rowCount:2});
});
test('reversing only visible rows or their positions cannot pass a correctly ordered response',()=>{
  const p=panel();p.rows.reverse();
  assert.throws(()=>assertRankBoardEvidence(reply(),'KILL',p),/顺序|名次/);
  const q=panel();[q.rows[0].y,q.rows[1].y]=[q.rows[1].y,q.rows[0].y];
  assert.throws(()=>assertRankBoardEvidence(reply(),'KILL',q),/顺序/);
});
test('name and value elsewhere cannot substitute for one missing row',()=>{
  const p=panel();p.rows[0].labels=['第 1 名  其他人','249'];p.labels.push('留守兵验收');
  assert.throws(()=>assertRankBoardEvidence(reply(),'KILL',p),/名次|名称/);
  const q=panel();q.rows[0].labels[1]='173';q.rows[1].labels[1]='249';
  assert.throws(()=>assertRankBoardEvidence(reply(),'KILL',q),/榜值/);
});
test('missing response, failed HTTP and failed envelope never become an empty success',()=>{
  for(const r of [null,{status:500,body:reply().body},{status:200,body:{code:1002}},
    {status:200,body:{code:0,data:{type:'KILL',page:1,pageSize:6}}}])
    assert.throws(()=>assertRankBoardEvidence(r,'KILL',panel([])));
});
test('wrong board or page, malformed rank and unsafe values fail closed',()=>{
  assert.throws(()=>assertRankBoardEvidence(reply(entries,'POWER'),'KILL',panel()),/榜类型/);
  const r=reply();r.body.data.page=2;assert.throws(()=>assertRankBoardEvidence(r,'KILL',panel()),/页码/);
  for(const changed of [{rank:0},{rank:1.5},{value:-1},{value:Number.MAX_SAFE_INTEGER+1},{name:''}]) {
    const rows=[{...entries[0],...changed}];assert.throws(()=>assertRankBoardEvidence(reply(rows),'KILL',panel(rows)));
  }
});
test('duplicate identities, duplicate ranks, wrong order and extra stale rows are rejected',()=>{
  for(const rows of [[entries[0],{...entries[1],id:entries[0].id}],
    [entries[0],{...entries[1],rank:1}],[entries[1],entries[0]]])
    assert.throws(()=>assertRankBoardEvidence(reply(rows),'KILL',panel(rows)));
  const p=panel();p.rows.push({labels:['第 3 名  旧数据','99']});
  assert.throws(()=>assertRankBoardEvidence(reply(),'KILL',p),/行数/);
});
test('populated response cannot pass with only global labels or a visible empty placeholder',()=>{
  const p=panel();p.rows=[];assert.throws(()=>assertRankBoardEvidence(reply(),'KILL',p),/行数/);
  const q=panel();q.labels.push('这个榜还没有人');assert.throws(()=>assertRankBoardEvidence(reply(),'KILL',q),/空榜/);
});
test('large values retain the exact thousands grouping and personal boards share the evidence rule',()=>{
  const rows=[{rank:1,id:'owned-large',name:'保留 兵力',value:1_234_567}];
  for(const type of ['POWER','KILL','WAR'])assert.equal(assertRankBoardEvidence(reply(rows,type),type,panel(rows)).rowCount,1);
  const p=panel(rows);p.rows[0].labels[1]='1,234,568';assert.throws(()=>assertRankBoardEvidence(reply(rows),'KILL',p),/榜值/);
});
