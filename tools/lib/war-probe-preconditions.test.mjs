import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canVerifySiegeSettlement, hasActiveWarForFatigue, fatigueWarAction } from './war-probe-preconditions.mjs';

test('preparation never starts the siege settlement clock but remains active for fatigue',()=>{
  const war={hasWar:true,phase:'PREPARATION',preparationAbandoned:false};
  assert.equal(canVerifySiegeSettlement(war),false);assert.equal(hasActiveWarForFatigue(war),true);
});
test('siege and completed siege can verify settlement; abandoned preparation cannot',()=>{
  assert.equal(canVerifySiegeSettlement({hasWar:true,phase:'SIEGE'}),true);
  assert.equal(canVerifySiegeSettlement({hasWar:true,phase:'SETTLED',preparationAbandoned:false}),true);
  assert.equal(canVerifySiegeSettlement({hasWar:true,phase:'SETTLED',preparationAbandoned:true}),false);
  assert.equal(hasActiveWarForFatigue({hasWar:true,phase:'SETTLED'}),false);
});
test('missing war data cannot pretend to be a siege or an active fatigue source',()=>{
  for(const value of [null,{}, {hasWar:false,phase:'SIEGE'}]){
    assert.equal(canVerifySiegeSettlement(value),false);assert.equal(hasActiveWarForFatigue(value),false);
  }
});
test('unknown or failed fatigue read never authorizes creation of another nation',()=>{
  for(const response of [null,{code:-1,data:null},{code:1001,data:{hasWar:true,phase:'SETTLED'}},
    {code:0,data:null},{code:0,data:{hasWar:false}},{code:0,data:{hasWar:true,phase:'UNKNOWN'}}])
    assert.equal(fatigueWarAction(response),'UNVERIFIED');
  assert.equal(fatigueWarAction({code:0,data:{hasWar:true,phase:'PREPARATION'}}),'REUSE');
  assert.equal(fatigueWarAction({code:0,data:{hasWar:true,phase:'SIEGE'}}),'REUSE');
  assert.equal(fatigueWarAction({code:0,data:{hasWar:true,phase:'SETTLED'}}),'DECLARE');
});
