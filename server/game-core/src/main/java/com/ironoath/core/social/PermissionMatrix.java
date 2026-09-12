package com.ironoath.core.social;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 职责：角色权限矩阵 —— 「某个职位能不能做某件事」的唯一裁决者（B10 §4、验收 4）。
 * 依赖：无（纯 Java，零框架 —— B00 分层规则：game-core 不碰 Spring，也读不到 game-config）。
 *
 * <p><b>B10 的头号禁止项是「不要把权限判断硬编码在代码里，必须走 role_permission.csv」</b>。
 * 所以本类不持有任何一条具体的权限规则：全部规则由 {@link #of} 从配置行灌进来，
 * 代码里只留下「怎么查」而不留下「查出来是什么」。
 * 验收 4 的判定方式正是「新增一个权限位只改配置，代码零改动，行为随之变化」。
 *
 * <p><b>为什么 game-core 不直接读 RolePermissionCfg</b>：分层规则要求 game-core 读不到 game-config
 * （否则纯 Java 的核心层就被配置加载器绑住了，批量平衡验证与单测都得先起一套配置环境）。
 * 所以这里定义一个形状中性的 {@link Grant}，由 game-web 的 service 层做一次翻译 ——
 * 与 {@code BattleParamsSource} → {@code BattleRules} 是同一种做法。
 *
 * <p><b>三档 tier 是配置表的形状决定的，不是本类的选择</b>：role_permission 表只有
 * allowLeader / allowOfficer / allowMember 三个布尔列。因此「副盟主」与「长老」
 * 在权限上无法区分（都落在 OFFICER 档）。这是配置表的表达力上限，
 * 不是代码写死的 —— 要区分就必须给表加一列，那属于 B16 的配置表结构变更。
 */
public final class PermissionMatrix {

    /** 权限所属的组织层级。与 role_permission 表的 scope 列一致。 */
    public enum Scope { SQUAD, ALLIANCE, NATION }

    /**
     * 角色档位。三档来自配置表的三个 allow* 列，不能多也不能少：
     * 多出来的档位在表里没有对应的列，查不到任何规则。
     */
    public enum Tier { LEADER, OFFICER, MEMBER }

    /**
     * 一条权限规则（= role_permission 表的一行）。
     *
     * @param scope      组织层级
     * @param permission 权限位标识（如 KICK_MEMBER、INITIATE_RALLY）
     * @param leader     首领档是否允许
     * @param officer    干部档是否允许
     * @param member     成员档是否允许
     */
    public record Grant(Scope scope, String permission, boolean leader, boolean officer, boolean member) {
        public Grant {
            if (scope == null) {
                throw new IllegalArgumentException("scope 不得为 null");
            }
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException("permission 不得为空：权限位是查表的键，空键会匹配到所有查询");
            }
        }

        boolean allows(Tier tier) {
            return switch (tier) {
                case LEADER -> leader;
                case OFFICER -> officer;
                case MEMBER -> member;
            };
        }
    }

    /** scope → (permission → grant)。两层 Map 让查询是 O(1)，而不是每次遍历 20 行配置。 */
    private final Map<Scope, Map<String, Grant>> grants;

    private PermissionMatrix(Map<Scope, Map<String, Grant>> grants) {
        this.grants = grants;
    }

    /**
     * 从配置行组装矩阵。
     *
     * <p>两条构造期校验，它们都是「配置写错了要立刻炸」而不是静默生效：
     * <ul>
     *   <li>同一 (scope, permission) 出现两行 ⇒ 抛错。两行规则意味着「踢人」这件事
     *       有两个答案，取哪一个取决于遍历顺序 —— 那种 bug 在改表之后才会出现，
     *       而且每次重启可能不一样</li>
     *   <li>一行都不给 ⇒ 抛错。空矩阵会让所有权限判定都返回 false，
     *       表现为「盟主什么都做不了」，而那看起来像是业务逻辑坏了，没人会想到是表没导出</li>
     * </ul>
     */
    public static PermissionMatrix of(Iterable<Grant> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("权限规则不得为 null（无规则请传空集合，但那会触发下一条校验）");
        }
        Map<Scope, Map<String, Grant>> byScope = new EnumMap<>(Scope.class);
        int total = 0;
        for (Grant grant : rows) {
            if (grant == null) {
                throw new IllegalArgumentException("权限规则里含 null 行");
            }
            Map<String, Grant> byPermission = byScope.computeIfAbsent(grant.scope(),
                    k -> new HashMap<>());
            Grant previous = byPermission.put(grant.permission(), grant);
            if (previous != null) {
                throw new IllegalArgumentException("权限位重复定义：" + grant.scope() + "/" + grant.permission()
                        + "。同一个权限有两条规则意味着判定结果取决于遍历顺序");
            }
            total++;
        }
        if (total == 0) {
            throw new IllegalArgumentException("权限矩阵为空：所有权限判定都会返回 false，"
                    + "表现为「盟主什么都做不了」。若确实要清空，请显式改代码而不是导出一张空表");
        }
        Map<Scope, Map<String, Grant>> immutable = new EnumMap<>(Scope.class);
        for (Map.Entry<Scope, Map<String, Grant>> entry : byScope.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        return new PermissionMatrix(Collections.unmodifiableMap(immutable));
    }

    /**
     * 某个职位能不能做某件事。
     *
     * <p><b>未登记的权限位一律返回 false</b>：新增一个功能却忘了在表里加规则时，
     * 默认拒绝比默认放行安全得多 —— 放行意味着任何成员都能执行一个没人审过的操作。
     * 而拒绝的表现是「盟主点不动」，那会立刻被发现。
     */
    public boolean allows(Scope scope, Tier tier, String permission) {
        if (scope == null || tier == null || permission == null) {
            return false;
        }
        Map<String, Grant> byPermission = grants.get(scope);
        if (byPermission == null) {
            return false;
        }
        Grant grant = byPermission.get(permission);
        return grant != null && grant.allows(tier);
    }

    /**
     * 某个职位在某一层级拥有的全部权限位。
     *
     * <p>这是给客户端的「结论」：{@code GET /social/permissions} 下发这个列表，
     * 客户端据此决定按钮灰不灰。<b>不下发整张矩阵</b> ——
     * 把矩阵给客户端等于把权限模型交出去，而客户端的任何判断都可以被绕过，
     * 真正的裁决始终在这里。
     *
     * <p>返回顺序按配置行的登记顺序稳定，便于抓包对比。
     */
    public Set<String> permissionsOf(Scope scope, Tier tier) {
        Map<String, Grant> byPermission = grants.get(scope);
        if (byPermission == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Grant> entry : byPermission.entrySet()) {
            if (entry.getValue().allows(tier)) {
                out.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** 某一层级登记了多少个权限位。用于断言「新增权限位只改了配置」。 */
    public int permissionCount(Scope scope) {
        Map<String, Grant> byPermission = grants.get(scope);
        return byPermission == null ? 0 : byPermission.size();
    }
}
