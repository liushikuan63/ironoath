/** 未进入围城不能把宣战时刻当成90分钟起点；筹备作废也不产生围城结算/季榜分。 */
export function canVerifySiegeSettlement(war) {
  return war?.hasWar===true && war.preparationAbandoned!==true
    && (war.phase==='SIEGE' || war.phase==='SETTLED');
}

/** PREPARATION本身仍是活跃战事，疲劳验证可复用它，不必再次宣战。 */
export function hasActiveWarForFatigue(war) {
  return war?.hasWar===true && war.preparationAbandoned!==true
    && (war.phase==='PREPARATION' || war.phase==='SIEGE');
}

/** 只有明确读到已结算才创建下一场；未知/失败的读取不授予新业务写入条件。 */
export function fatigueWarAction(response) {
  if(response?.code!==0)return 'UNVERIFIED';
  if(hasActiveWarForFatigue(response.data))return 'REUSE';
  if(response.data?.hasWar===true && response.data.phase==='SETTLED')return 'DECLARE';
  return 'UNVERIFIED';
}

/**
 * A strategic March can expose FIGHTING while its persisted COMBAT recovery is
 * still pending. The War gate-count receipt plus a non-active March state is
 * the completion boundary; a return without a War-count change is a terminal
 * no-capture result. Unknown/missing state stays pending.
 */
export function gateBattleOutcome({marchStatus,previousGateCount,currentGateCount}={}) {
  if(!Number.isInteger(previousGateCount) || previousGateCount<0
      || !Number.isInteger(currentGateCount) || currentGateCount<previousGateCount)return 'UNVERIFIED';
  if(marchStatus==='MARCHING' || marchStatus==='FIGHTING')return 'PENDING';
  if(currentGateCount>previousGateCount)return 'CAPTURED';
  if(marchStatus==='RETURNING' || marchStatus==='ANNIHILATED')return 'NO_CAPTURE';
  return 'PENDING';
}
