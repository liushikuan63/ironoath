package com.ironoath.web.battle;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单玩家战略伤兵的可重放存储步骤。调用者须先证明目标操作归属、真实成员与战损，
 * 冻结原目标代次/操作号/伤兵顺序/医院容量；本步骤不授予战斗权，也不自动创建丢失的军队。
 */
public final class StrategicWoundSettlement {
    private StrategicWoundSettlement() {}

    public static ArmyState.StrategicWoundReceipt admit(ArmyRepository armies,String playerId,
            String targetId,long sequence,String operationId,Map<String,Long> wounds,long hospitalCapacity) {
        if(playerId==null || playerId.isBlank() || wounds==null)
            throw new IllegalArgumentException("战略伤兵玩家/明细不得为空");
        var frozen=Collections.unmodifiableMap(new LinkedHashMap<>(wounds));
        for(int attempt=0;attempt<2;attempt++) {
            long version=armies.versionOf(playerId);
            var army=armies.findByPlayerId(playerId).orElseThrow(
                    ()->new IllegalStateException("战略伤兵原军队档缺失，不能创建空档掩盖来源"));
            var previous=army.snapshot().strategicWounds().get(targetId);
            var receipt=army.admitStrategicWoundedOnce(targetId,sequence,operationId,frozen,hospitalCapacity);
            if(receipt.equals(previous))return receipt;
            try {
                armies.save(playerId,army,version);
                return receipt;
            } catch(RuntimeException unknownWrite) {
                try {
                    var persisted=armies.findByPlayerId(playerId).orElseThrow(
                            ()->new IllegalStateException("战略伤兵写入后无法独立读回军队"));
                    var known=persisted.snapshot().strategicWounds().get(targetId);
                    if(known!=null && known.sequence()==sequence && known.operationId().equals(operationId)) {
                        // 复用领域的完整载荷/顺序校验；返回实际已提交的收容结果，而非重算容量。
                        return persisted.admitStrategicWoundedOnce(targetId,sequence,operationId,frozen,hospitalCapacity);
                    }
                } catch(RuntimeException readFailure) {
                    if(readFailure!=unknownWrite)unknownWrite.addSuppressed(readFailure);
                    throw unknownWrite;
                }
                if(attempt==1)throw unknownWrite;
            }
        }
        throw new IllegalStateException("战略伤兵重试未产生结果");
    }
}
