package com.ironoath.core.social;

/**
 * 职责：联盟职位（B10 §4 权限矩阵的 alliance scope）。
 * 依赖：PermissionMatrix.Tier。
 *
 * <p>四级来自 B10 §2 的「盟主 / 副盟主 / 长老 / 成员」。
 *
 * <p><b>已知表达力缺口（记录待修）</b>：role_permission 表只有 allowLeader / allowOfficer /
 * allowMember 三个布尔列，所以 OFFICER（副盟主）与 ELDER（长老）在权限上<b>无法区分</b>，
 * 两者都落在 {@link PermissionMatrix.Tier#OFFICER} 档。
 *
 * <p>这个映射写在这里而不是散在各处，是为了让缺口只有一个落点：
 * 将来给表加一列 allowElder 时，改这一个方法就能让两个职位分开，
 * 业务代码一行都不用动（B10 验收 4 要的正是「权限变化只改配置」）。
 * 在那之前，「长老」是一个荣誉称号而不是一个权限档位 —— 这不算错，
 * 但必须写清楚，否则下一个人会以为长老的权限是代码里漏判了。
 */
public enum AllianceRole {
    /** 盟主。唯一能转让、解散、扩容的人。 */
    LEADER,
    /** 副盟主。 */
    OFFICER,
    /** 长老。当前与副盟主同权限档，理由见类注释。 */
    ELDER,
    /** 普通成员。 */
    MEMBER;

    /** 映射到权限矩阵的档位。 */
    public PermissionMatrix.Tier tier() {
        return switch (this) {
            case LEADER -> PermissionMatrix.Tier.LEADER;
            case OFFICER, ELDER -> PermissionMatrix.Tier.OFFICER;
            case MEMBER -> PermissionMatrix.Tier.MEMBER;
        };
    }

    /** 从高到低的顺序。用于「不能任命比自己高的职位」这类校验。 */
    public int rank() {
        return switch (this) {
            case LEADER -> 3;
            case OFFICER -> 2;
            case ELDER -> 1;
            case MEMBER -> 0;
        };
    }
}
