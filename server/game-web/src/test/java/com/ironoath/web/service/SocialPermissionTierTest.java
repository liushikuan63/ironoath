package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ironoath.core.nation.Nation;
import com.ironoath.core.nation.NationPermissions;
import com.ironoath.core.social.PermissionMatrix;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职位串到权限档位的映射。守的是两类相反的错误：把有权限的人判成没权限，
 * 以及把认不出来的串当成「什么都不拦」放过去。
 *
 * <p>{@code tierOf} 是包内可见的，因为「存档里残留一个已经不存在的职位名」这件事
 * 用公共 API 造不出来 —— 而它恰恰是这条路径上唯一会静默改变玩家权限的输入。
 */
@DisplayName("B10 职位串识别：认不出来的职位既不授权也不炸响应")
class SocialPermissionTierTest {

    @Test
    @DisplayName("四个联盟职位与两个小队职位都映射到矩阵里的档位")
    void knownRolesMapToTiers() {
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.ALLIANCE, "LEADER"))
                .isEqualTo(PermissionMatrix.Tier.LEADER);
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.ALLIANCE, "OFFICER"))
                .isEqualTo(PermissionMatrix.Tier.OFFICER);
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.ALLIANCE, "ELDER"))
                .as("长老当前与副盟主同权限档（理由写在 AllianceRole 类注释）")
                .isEqualTo(PermissionMatrix.Tier.OFFICER);
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.ALLIANCE, "MEMBER"))
                .isEqualTo(PermissionMatrix.Tier.MEMBER);
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.SQUAD, "LEADER"))
                .isEqualTo(PermissionMatrix.Tier.LEADER);
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.SQUAD, "MEMBER"))
                .isEqualTo(PermissionMatrix.Tier.MEMBER);
    }

    @Test
    @DisplayName("没有职位不是错误：NONE 与 null 都安静地回「无档位」")
    void noRoleIsNotAFault() {
        for (String role : new String[] {"NONE", "null", null}) {
            for (PermissionMatrix.Scope scope : PermissionMatrix.Scope.values()) {
                assertThat(SocialAppService.tierOf(scope, role))
                        .as("scope=%s role=%s 应当视为无职位", scope, role)
                        .isNull();
            }
        }
    }

    @Test
    @DisplayName("认不出的职位串：两个 scope 同一条口径 —— 拒绝授权，但不许抛到玩家脸上")
    void unknownRoleDeniesInsteadOfCrashing() {
        // 判别性：改之前联盟侧静默回 null、小队侧直接抛 IllegalArgumentException，
        // 于是同一个坏数据在两条路径上表现成两种完全不同的故障。
        for (String role : new String[] {"LEADR", "leader", "OWNER", "  LEADER  ", "LEADER;"}) {
            for (PermissionMatrix.Scope scope : List.of(PermissionMatrix.Scope.SQUAD,
                    PermissionMatrix.Scope.ALLIANCE, PermissionMatrix.Scope.NATION)) {
                assertThatCode(() -> SocialAppService.tierOf(scope, role))
                        .as("坏职位串不许冒出未捕获异常（scope=%s role=%s）", scope, role)
                        .doesNotThrowAnyException();
                assertThat(SocialAppService.tierOf(scope, role))
                        .as("认不出来的职位不许被当成最高档放行").isNull();
            }
        }
    }

    @Test
    @DisplayName("国家认的是**官职名**：拿联盟的职位串来一个都不认")
    void nationAcceptsOfficesNotAllianceRoles() {
        // 判别性（这条用例的本意从 B13 之前就没变过）：改之前是「非 SQUAD 就按 AllianceRole 认」，
        // 于是 NATION + LEADER 会凭空继承一档权限。现在国家有自己的一套名字，但**联盟的名字仍然不许进来**。
        for (String allianceRole : new String[] {"LEADER", "OFFICER", "ELDER", "NONE"}) {
            assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.NATION, allianceRole))
                    .as("联盟职位串 %s 不许被当成国家官职", allianceRole).isNull();
        }
        // 六个官职各归哪一档（映射的家在 core/nation/NationPermissions，与写路径共用同一份）
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.NATION, "KING"))
                .isEqualTo(PermissionMatrix.Tier.LEADER);
        for (String officer : new String[] {"PRIME_MINISTER", "GENERAL", "MINISTER", "DIPLOMAT"}) {
            assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.NATION, officer))
                    .as("%s 是干部档", officer).isEqualTo(PermissionMatrix.Tier.OFFICER);
        }
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.NATION, "REPRESENTATIVE"))
                .as("议员是派生席位，与普通国民同档").isEqualTo(PermissionMatrix.Tier.MEMBER);
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.NATION, "MEMBER"))
                .as("「在国里但没官职」用 MEMBER 表达这一态（role_permission 表里这一档就叫 member）")
                .isEqualTo(PermissionMatrix.Tier.MEMBER);
    }

    @Test
    @DisplayName("官职映射只有一个家：写路径与读路径对六个官职给出同一档")
    void oneMappingForBothPaths() {
        // 判别性：两处各写一遍时，症状是"面板上那颗键亮着、点下去被拒"（或反过来），
        // 而两边都不会报错。这条把两份实现钉在一起：任何一份改了而另一份没改，这里立刻红。
        for (Nation.Office office : Nation.Office.values()) {
            assertThat(NationPermissions.tierOf(office))
                    .as("core 那份映射要覆盖 %s", office).isNotNull();
            assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.NATION, office.name()))
                    .as("读路径（%s）与 core 那份必须一致", office)
                    .isEqualTo(NationPermissions.tierOf(office));
        }
        // 六个官职一个都不许漏：漏掉的那个会在读路径上被当成"认不出的串"⇒ 权限静默变空
        assertThat(Nation.Office.values()).hasSize(6);
        assertThat(NationPermissions.tierOfName("NO_SUCH_OFFICE")).isNull();
        assertThat(NationPermissions.tierOfName(null)).isNull();
    }
}
