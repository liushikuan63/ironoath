package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
    @DisplayName("国家还没有职位体系：拿联盟的职位串来也拿不到档位")
    void nationHasNoRolesYet() {
        // 判别性：改之前是「非 SQUAD 就按 AllianceRole 认」，于是 NATION + LEADER 会凭空继承一档权限
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.NATION, "LEADER"))
                .as("B13 之前国家没有职位枚举，不许按联盟的表认").isNull();
        assertThat(SocialAppService.tierOf(PermissionMatrix.Scope.NATION, "MEMBER")).isNull();
    }
}
