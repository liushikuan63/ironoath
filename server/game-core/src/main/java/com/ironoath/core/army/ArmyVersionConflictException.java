package com.ironoath.core.army;

/** Army 快照或 CAS 版本冲突；本次保存确定未写入，调用方可补偿已扣费用。 */
public final class ArmyVersionConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public ArmyVersionConflictException(String message) {
        super(message);
    }
}
