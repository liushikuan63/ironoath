package com.ironoath.core.power;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B08 §7 三条基础保护的验证 —— 验收 14（护盾期禁主动攻击）与验收 15（新手保护先到者生效）。
 * 依赖：JUnit 5 + AssertJ；纯 Java，不需要容器（B00：逻辑与容器分离）。
 *
 * <p><b>本类同时守住「只有三条」这条红线</b>。三条之外的任何保护都会阻止某个社交行为，
 * 而 C00 公理二的原话是「数一数保护规则的数量，超过三条就要问自己这条规则会阻止哪个社交行为」。
 * 数量本身由 {@code PowerContractParityTest} 在配置表层断言，
 * 本类断言的是行为层：这三条各自的条件、到期、与「禁主动攻击」这个对价。
 */
class ProtectionTest {

    private static final long HOUR = 3600_000L;
    private static final long NOW = 1_800_000_000_000L;

    /** 与 global 表 v18 一致的规则：8 级解除 / 24h 窗口 / 3 人 4h / 5 人 12h。 */
    private static Protection.Rules rules() {
        return new Protection.Rules(
                8,
                24L * HOUR,
                3, 4L * HOUR,
                5, 12L * HOUR);
    }

    // ---------- 验收 15：新手保护 ----------

    @Test
    @DisplayName("验收15：新手保护 72h 或主城 8 级，先到者生效")
    void newbieProtectionEndsAtWhicheverComesFirst() {
        Protection.Rules rules = rules();
        // 72h 这一半由建号时写入的 protectUntil 表达（PlayerInitService 读 NEWCOMER_PROTECT_SECONDS），
        // Protection 只负责「时间没到 且 等级没到」这个合取 —— 合取天然就是「先到者生效」
        long protectUntil = NOW + 62L * HOUR;   // 建号 10 小时，还剩 62 小时

        assertThat(Protection.newbieProtected(NOW, 3, protectUntil, rules))
                .as("注册 10 小时、主城 3 级：两个条件都没到，必须仍然受保护").isTrue();
        assertThat(Protection.newbieProtected(protectUntil + 1L, 3, protectUntil, rules))
                .as("满 72 小时即解除，哪怕主城还是 1 级").isFalse();
        assertThat(Protection.newbieProtected(NOW, 8, protectUntil, rules))
                .as("主城到 8 级即解除，哪怕只过了 10 小时。"
                        + "只看时间的话，一个 20 小时冲到 8 级的强度玩家会被继续保护，"
                        + "而他早就不是需要保护的新手了").isFalse();
        assertThat(Protection.newbieProtected(NOW, 20, protectUntil, rules))
                .as("超过 8 级同样解除").isFalse();
    }

    @Test
    @DisplayName("主动攻击后新手保护被解除（protectUntil 置 null），且不会因为时间没过就恢复")
    void newbieProtectionReleasedByAttackingStaysReleased() {
        Protection.Rules rules = rules();
        long protectUntil = NOW + 71L * HOUR;
        assertThat(Protection.newbieProtected(NOW, 3, protectUntil, rules)).isTrue();

        // B08 §7：主动攻击则解除。解除就是把 protectUntil 置 null。
        // 这里刻意不做「用 createdAt 兜底重算」—— 兜底会把玩家刚刚放弃的保护又还回去，
        // 那个二次确认弹窗就成了骗局
        assertThat(Protection.newbieProtected(NOW, 3, null, rules))
                .as("已主动放弃保护：时间还剩 71 小时也不该恢复").isFalse();
        assertThat(Protection.statusOf(NOW, 3, null, null, null, rules).isProtected()).isFalse();
    }

    @Test
    @DisplayName("保护状态给出明确文案，且不返回一个「保护中但没有到期时刻」的非法状态")
    void protectionStatusCarriesUsableMessage() {
        Protection.Rules rules = rules();
        Protection.Status newbie = Protection.statusOf(NOW, 3, NOW + 71 * HOUR, null, null, rules);
        assertThat(newbie.kind()).isEqualTo(Protection.Kind.NEWBIE);
        assertThat(newbie.untilMillis()).isEqualTo(NOW + 71 * HOUR);
        assertThat(newbie.message()).as("拒绝必须带文案（B08：绝不静默失败）").contains("新手保护");
        assertThat(newbie.blocksActiveAttack())
                .as("三条保护都禁主动攻击：护盾必须付对价，否则就是免费的进攻准备期").isTrue();

        Protection.Status shielded = Protection.statusOf(NOW, 20, null, NOW + 3 * HOUR, null, rules);
        assertThat(shielded.kind()).isEqualTo(Protection.Kind.VICTIM_SHIELD);
        assertThat(shielded.message()).contains("免战");

        Protection.Status none = Protection.statusOf(NOW, 20, null, null, null, rules);
        assertThat(none.kind()).isEqualTo(Protection.Kind.NONE);
        assertThat(none.isProtected()).isFalse();
        assertThat(none.blocksActiveAttack()).as("无保护时当然可以主动攻击").isFalse();

        // 新手保护优先于受害护盾：两者同时生效时报「新手保护」，
        // 因为那是玩家能主动结束的那一个（主动攻击即解除），提示更有行动价值
        Protection.Status both = Protection.statusOf(NOW, 3, NOW + 71 * HOUR, NOW + 3 * HOUR, null, rules);
        assertThat(both.kind()).isEqualTo(Protection.Kind.NEWBIE);
    }

    // ---------- 免战牌（自愿停战） ----------

    @Test
    @DisplayName("免战牌：只挡到到期那一刻，双向都受限，且不覆盖受害护盾的判定")
    void peaceShieldBlocksBothWaysUntilExpiry() {
        Protection.Rules rules = rules();

        Protection.Status peace = Protection.statusOf(NOW, 20, null, null, NOW + 6 * HOUR, rules);
        assertThat(peace.kind()).isEqualTo(Protection.Kind.PEACE);
        assertThat(peace.isProtected()).as("期间不可被攻击").isTrue();
        assertThat(peace.blocksActiveAttack()).as("期间也不可主动出击：停战是双向的").isTrue();
        assertThat(peace.message()).as("文案要说清这是自愿停战，不是系统把他保护起来了")
                .contains("自愿停战");

        assertThat(Protection.statusOf(NOW, 20, null, null, NOW, rules).kind())
                .as("到期那一刻就失效（左闭右开）：多留一秒就是白送一秒无敌").isEqualTo(Protection.Kind.NONE);
        assertThat(Protection.statusOf(NOW, 20, null, NOW + 2 * HOUR, NOW + 6 * HOUR, rules).kind())
                .as("同时生效时报受害护盾：那是系统判给的、玩家更该先知道")
                .isEqualTo(Protection.Kind.VICTIM_SHIELD);

        com.ironoath.core.player.PlayerPvp empty = com.ironoath.core.player.PlayerPvp.empty();
        assertThat(empty.withPeaceUntil(NOW + 6 * HOUR).withPeaceUntil(NOW + 2 * HOUR).peaceUntil())
                .as("连用两张不该把已有的停战倒扣回去：付费内容只延长不缩短")
                .isEqualTo(NOW + 6 * HOUR);
    }

    // ---------- 连续受害护盾 ----------

    @Test
    @DisplayName("受害护盾按不同攻击者计数：同一个人连打五次不触发，三个不同的人才触发 4h")
    void victimShieldCountsDistinctAttackersNotHits() {
        Protection.Rules rules = rules();
        Map<String, Long> hits = new LinkedHashMap<>();

        // 同一个大佬连打三次
        for (int i = 0; i < 3; i++) {
            Protection.VictimOutcome outcome =
                    Protection.onAttacked(hits, "bully", NOW + i * 1000L, null, rules);
            hits = new LinkedHashMap<>(outcome.retainedHits());
            assertThat(outcome.distinctAttackers())
                    .as("同一个人重复攻击不增加计数：否则大佬可以把目标永久锁进护盾，"
                            + "那等于变相禁止攻击（C00 公理一）").isEqualTo(1);
            assertThat(outcome.shieldUntil()).isNull();
        }

        // 换成三个不同的人
        hits.clear();
        Protection.VictimOutcome second = Protection.onAttacked(hits, "a", NOW, null, rules);
        assertThat(second.distinctAttackers()).isEqualTo(1);
        Protection.VictimOutcome third =
                Protection.onAttacked(second.retainedHits(), "b", NOW, null, rules);
        assertThat(third.distinctAttackers()).isEqualTo(2);
        assertThat(third.shieldUntil()).as("两个人还不够").isNull();

        Protection.VictimOutcome fourth =
                Protection.onAttacked(third.retainedHits(), "c", NOW, null, rules);
        assertThat(fourth.distinctAttackers()).isEqualTo(3);
        assertThat(fourth.shieldUntil()).as("第三个人触发第一档").isEqualTo(NOW + 4 * HOUR);
        assertThat(fourth.shieldRaised()).isTrue();
    }

    @Test
    @DisplayName("第五个不同攻击者把护盾升到 12h，且护盾只推后不缩短")
    void victimShieldEscalatesAndNeverShortens() {
        Protection.Rules rules = rules();
        Map<String, Long> hits = new LinkedHashMap<>();
        Long shield = null;
        for (String attacker : new String[]{"a", "b", "c", "d"}) {
            Protection.VictimOutcome outcome = Protection.onAttacked(hits, attacker, NOW, shield, rules);
            hits = new LinkedHashMap<>(outcome.retainedHits());
            shield = outcome.shieldUntil();
        }
        assertThat(shield).as("四人时仍是第一档 4h").isEqualTo(NOW + 4 * HOUR);

        Protection.VictimOutcome fifth = Protection.onAttacked(hits, "e", NOW, shield, rules);
        assertThat(fifth.distinctAttackers()).isEqualTo(5);
        assertThat(fifth.shieldUntil()).as("第五人升到第二档 12h").isEqualTo(NOW + 12 * HOUR);
        assertThat(fifth.shieldRaised()).isTrue();

        // 已经处于 12h 护盾中，第六个人来打：不能把剩余时间重置成 4h，
        // 否则打人方能靠控制人数把对方的护盾「调短」
        Protection.VictimOutcome sixth = Protection.onAttacked(
                fifth.retainedHits(), "f", NOW + HOUR, fifth.shieldUntil(), rules);
        assertThat(sixth.distinctAttackers()).as("第六个人确实进了计数").isEqualTo(6);
        assertThat(sixth.shieldUntil())
                .as("第六个人不再续期护盾：门槛是一次性台阶，不是持续刷新").isEqualTo(NOW + 12 * HOUR);
        assertThat(sixth.shieldRaised()).isFalse();
    }

    @Test
    @DisplayName("窗口外的攻击记录会被丢弃，护盾因此不会被无限续期")
    void victimShieldWindowExpires() {
        Protection.Rules rules = rules();
        Map<String, Long> hits = new LinkedHashMap<>();
        hits.put("a", NOW - 25 * HOUR);
        hits.put("b", NOW - 25 * HOUR);
        hits.put("c", NOW - HOUR);

        Protection.VictimOutcome outcome = Protection.onAttacked(hits, "d", NOW, null, rules);
        assertThat(outcome.retainedHits())
                .as("24h 窗口外的两条记录必须被丢弃，否则这个 map 会随玩家被打的次数单调增长，"
                        + "而它是存在存档里的")
                .containsOnlyKeys("c", "d");
        assertThat(outcome.distinctAttackers()).isEqualTo(2);
        assertThat(outcome.shieldUntil()).as("过期后重新从 0 数，两个人不触发").isNull();
    }

    @Test
    @DisplayName("命中记录数量被第二档门槛封顶，不会随被全服围殴而无限膨胀")
    void victimShieldHitsAreCapped() {
        Protection.Rules rules = rules();
        Map<String, Long> hits = new LinkedHashMap<>();
        Long shield = null;
        for (int i = 0; i < 40; i++) {
            Protection.VictimOutcome outcome =
                    Protection.onAttacked(hits, "attacker_" + i, NOW + i, shield, rules);
            hits = new LinkedHashMap<>(outcome.retainedHits());
            shield = outcome.shieldUntil();
        }
        assertThat(hits).as("封顶在「第二档门槛 + 1」条：多留一条才能区分「正好达到门槛」"
                + "与「已经超过门槛」").hasSize(6);
        // 护盾只在第 5 个攻击者到达时授予一次，之后 35 个人都不再续期。
        // 若每个新攻击者都刷新，被全服围殴的人会永远处于免战、也永远出不了兵
        assertThat(shield).isEqualTo(NOW + 4L + 12 * HOUR);
    }

    // ---------- 规则校验 ----------

    @Test
    @DisplayName("规则本身不合法时立刻报错：两档必须严格递进，窗口必须为正")
    void rulesRejectNonsensicalConfiguration() {
        assertThatThrownBy(() -> new Protection.Rules(8, 0L, 3, 4 * HOUR, 5, 12 * HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .as("窗口为 0 会让护盾永不触发 —— 而这是静默失败，玩家只会被反复打")
                .hasMessageContaining("窗口");
        assertThatThrownBy(() -> new Protection.Rules(8, 24 * HOUR, 5, 4 * HOUR, 3, 12 * HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .as("第二档门槛必须高于第一档，否则第二档永远不触发")
                .hasMessageContaining("触发人数");
        assertThatThrownBy(() -> new Protection.Rules(8, 24 * HOUR, 3, 12 * HOUR, 5, 4 * HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .as("打得越狠护盾反而越短，升级台阶就失去意义")
                .hasMessageContaining("护盾时长");
        assertThatThrownBy(() -> new Protection.Rules(0, 24 * HOUR, 3, 4 * HOUR, 5, 12 * HOUR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("等级解除线");
    }

    @Test
    @DisplayName("生效中的保护必须带正的到期时刻与文案，无保护时两者都必须为空")
    void statusRejectsInconsistentConstruction() {
        assertThatThrownBy(() -> new Protection.Status(Protection.Kind.NEWBIE, 0L, "文案"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("到期时刻");
        assertThatThrownBy(() -> new Protection.Status(Protection.Kind.NEWBIE, NOW, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("文案");
        assertThatThrownBy(() -> new Protection.Status(Protection.Kind.NONE, NOW, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无保护");
        assertThatThrownBy(() -> new Protection.Status(Protection.Kind.NONE, 0L, "不该有文案"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不该带文案");
    }
}
