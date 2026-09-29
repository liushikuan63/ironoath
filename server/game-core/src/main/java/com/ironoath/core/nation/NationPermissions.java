package com.ironoath.core.nation;

import com.ironoath.core.social.PermissionMatrix;

/**
 * 职责：国家官职 → 权限档位的映射。**全项目唯一的一份**。
 * 依赖：{@link Nation.Office}（同模块）与 {@link PermissionMatrix}（同模块，纯 Java）。
 *
 * <p><b>为什么单独一个类，而不是写在某个 service 里</b>：这份映射有**两个读点** ——
 * 写路径（{@code NationAppService.requirePermission}：任命、外交、研究、支取国库）
 * 与读路径（{@code GET /social/permissions?scope=NATION}：面板据此决定按钮灰不灰）。
 * 两处各写一遍的后果与 {@code NationLeaders} 那条同源：**分叉时没有任何报错**，
 * 症状只是"面板上那颗键亮着，点下去被拒"或反过来 —— 而两者都看起来像"某处偶发"。
 *
 * <p><b>为什么不是 PermissionMatrix 的一个静态方法</b>：那个类在注释里写明了
 * 「依赖：无（纯 Java，零框架）」，而且它刻意不持有任何具体规则（B10 头号禁止项：
 * 权限规则只许来自 {@code role_permission} 表）。把 {@code Nation.Office} 这类领域枚举
 * 塞进去，就是把"哪一档"这件事从配置表的形状里挪进矩阵本体 —— 那正是它拒绝做的事。
 * 这里做的是**结构映射**（哪一档官职），"具体能不能做某事"仍然只有 {@code role_permission} 表说了算。
 *
 * <p><b>三档从哪来</b>：{@code role_permission} 只有 allowLeader / allowOfficer / allowMember 三列，
 * 所以六个官职落到三档是表的表达力上限，不是本类的选择（要按官职细分得给表加列，见 B16）。
 * 国王独占 LEADER；四个实职（首相 / 大将军 / 内政官 / 外交官）同为 OFFICER；
 * 议员是**派生席位**（每盟主一席，没人任命它），与"在国里但没有官职"的普通国民同档。
 */
public final class NationPermissions {

    private NationPermissions() {
    }

    /** 不在任何国家里（{@code /social/permissions} 的 role 字段用它）。 */
    public static final String NO_ROLE = "NONE";

    /**
     * "在国里但没有官职"。**刻意与 {@link PermissionMatrix.Tier#MEMBER} 同名**：
     * 这一档在 {@code role_permission} 表里就叫 member，凭空造一个别名只会让读的人
     * 以为国家还有第五档职位。
     */
    public static final String MEMBER_ROLE = "MEMBER";

    /**
     * 官职 → 档位。{@code null}（不在任何国家 / 在国里但没官职）落 MEMBER 档 ——
     * 与 {@code NationAppService} 判定"普通国民能不能参加国家集结"时用的是同一档。
     */
    public static PermissionMatrix.Tier tierOf(Nation.Office office) {
        if (office == null) {
            return PermissionMatrix.Tier.MEMBER;
        }
        return switch (office) {
            case KING -> PermissionMatrix.Tier.LEADER;
            case PRIME_MINISTER, GENERAL, MINISTER, DIPLOMAT -> PermissionMatrix.Tier.OFFICER;
            case REPRESENTATIVE -> PermissionMatrix.Tier.MEMBER;
        };
    }

    /**
     * 官职名 → 档位。**认不出来一律回 {@code null}（= 无档位 = 什么都不许）**，
     * 不抛异常：这条路上唯一的坏输入是"存档里残留一个已经不存在的官职名"，
     * 而它不该让查看权限的人吃一个 500。
     *
     * <p>回 null 还有一层判别性：**联盟的职位串（LEADER / OFFICER / ELDER）在这里必须认不出来**。
     * 早先的实现是"非 SQUAD 就按 AllianceRole 认"，于是给一个 NATION 查询传 LEADER
     * 就会凭空继承一档国家权限 —— 那正是本方法要挡的形状（用例见 SocialPermissionTierTest）。
     */
    public static PermissionMatrix.Tier tierOfName(String officeName) {
        if (officeName == null || officeName.isBlank() || NO_ROLE.equals(officeName)) {
            return null;
        }
        if (MEMBER_ROLE.equals(officeName)) {
            // "在国里但没官职"：调用方（SocialAppService）用它表达这一态
            return PermissionMatrix.Tier.MEMBER;
        }
        try {
            return tierOf(Nation.Office.valueOf(officeName));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
