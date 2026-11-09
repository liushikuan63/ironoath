import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canVerifySiegeSettlement, hasActiveWarForFatigue, fatigueWarAction, gateBattleOutcome } from './war-probe-preconditions.mjs';

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

test('a FIGHTING gate March stays pending until the completed War gate receipt is observable',()=>{
  const cases=[
    {marchStatus:'FIGHTING',previousGateCount:0,currentGateCount:0,want:'PENDING'},
    {marchStatus:'FIGHTING',previousGateCount:0,currentGateCount:1,want:'PENDING'},
    {marchStatus:'STATIONED',previousGateCount:0,currentGateCount:1,want:'CAPTURED'},
    {marchStatus:'RETURNING',previousGateCount:0,currentGateCount:0,want:'NO_CAPTURE'},
    {marchStatus:'ANNIHILATED',previousGateCount:1,currentGateCount:1,want:'NO_CAPTURE'},
    {marchStatus:'STATIONED',previousGateCount:0,currentGateCount:0,want:'PENDING'},
    {marchStatus:'FIGHTING',previousGateCount:-1,currentGateCount:0,want:'UNVERIFIED'},
  ];
  for(const c of cases)assert.equal(gateBattleOutcome(c),c.want,JSON.stringify(c));
});
