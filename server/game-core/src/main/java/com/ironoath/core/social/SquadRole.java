package com.ironoath.core.social;

/**
 * 职责：小队职位（B10 §4 权限矩阵的 squad scope）。
 * 依赖：PermissionMatrix.Tier。
 *
 * <p><b>只有两级，这是刻意的</b>：小队 5~10 人，满足的是 C00 公理七说的「我和兄弟们」这种熟人需求，
 * 熟人圈子里没有副队长 —— 多一层职位就多一层「为什么他是副队长不是我」的摩擦，
 * 而小队存在的全部价值就是低摩擦。联盟才需要四级（30~150 人必须有管理层）。
 */
public enum SquadRole {
    /** 队长。创建者，也是 B10 §1「人数上限第二档门槛」的承担者（队长主城 8 级才开到 8 人）。 */
    LEADER,
    /** 队员。 */
    MEMBER;

    /** 映射到权限矩阵的档位。小队没有干部档，所以只有两个取值。 */
    public PermissionMatrix.Tier tier() {
        return this == LEADER ? PermissionMatrix.Tier.LEADER : PermissionMatrix.Tier.MEMBER;
    }
}
