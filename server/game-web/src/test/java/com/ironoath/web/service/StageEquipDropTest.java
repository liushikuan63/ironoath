package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.cfg.EquipCfg;

/**
 * 职责：通章送装备的**规则**（B20 装备获取来源 (b) 的纯规则用例）。
 * 依赖：无（只有规则与几张编造的行，不起容器）。
 *
 * <p><b>为什么这条用例必须存在</b>：规则里那个"按稀有度筛表"的判定，第一版写成了
 * {@code "N".equals(row.rarity())} —— `rarity` 其实是枚举，于是筛选恒为空、悄悄一件都不发，
 * 而编译与端到端都可能看不出来（端到端只在"通章"那一刻才暴露）。这里的断言就是冲着它来的。
 */
class StageEquipDropTest {

    private static EquipCfg row(String id, EquipCfg.Rarity rarity) {
        return new EquipCfg(id, "测试装备", EquipCfg.Slot.WEAPON, rarity,
                1L, 1L, 1L, 1L, "set_test", 10L);
    }

    private static final List<EquipCfg> TABLE = List.of(
            row("eq_a", EquipCfg.Rarity.N),
            row("eq_b", EquipCfg.Rarity.N),
            row("eq_c", EquipCfg.Rarity.SR));

    @Test
    @DisplayName("第 1 章发 N 装、第 5 章起发 SR 装：阈值是唯一旋钮")
    void rarityFollowsTheChapterThreshold() {
        assertThat(StageEquipDrop.pick(1L, TABLE, 5L).rarity()).isEqualTo(EquipCfg.Rarity.N);
        assertThat(StageEquipDrop.pick(4L, TABLE, 5L).rarity()).isEqualTo(EquipCfg.Rarity.N);
        assertThat(StageEquipDrop.pick(5L, TABLE, 5L).rarity()).isEqualTo(EquipCfg.Rarity.SR);
        assertThat(StageEquipDrop.pick(99L, TABLE, 5L).rarity()).isEqualTo(EquipCfg.Rarity.SR);
    }

    @Test
    @DisplayName("同一档里按章号轮转：连着两章不会发同一件")
    void rotationWalksTheCandidates() {
        List<EquipCfg> fourN = List.of(
                row("eq_a", EquipCfg.Rarity.N), row("eq_b", EquipCfg.Rarity.N),
                row("eq_c", EquipCfg.Rarity.N), row("eq_d", EquipCfg.Rarity.N));

        String first = StageEquipDrop.pick(1L, fourN, 99L).id();
        String second = StageEquipDrop.pick(2L, fourN, 99L).id();
        String fifth = StageEquipDrop.pick(5L, fourN, 99L).id();

        assertThat(second).as("连着两章发同一件就等于没有轮转").isNotEqualTo(first);
        assertThat(fifth).as("第 5 章回到第 1 章那一件（按候选数取模），仍然确定可复跑").isEqualTo(first);
        assertThat(StageEquipDrop.pick(2L, fourN, 99L).id())
                .as("同一章问两次必须给出同一件：通章奖励是确定性的进度奖励，不是抽奖")
                .isEqualTo(second);
    }

    @Test
    @DisplayName("表里缺该档时返回 null：缺行的症状是「这章没发 + 日志说得清」，不是随便发一件")
    void missingRarityRowYieldsNull() {
        List<EquipCfg> onlySr = List.of(row("eq_sr", EquipCfg.Rarity.SR));

        assertThat(StageEquipDrop.pick(1L, onlySr, 5L)).as("第 1 章要 N 装而表里只有 SR").isNull();
        assertThat(StageEquipDrop.pick(1L, List.of(), 5L)).isNull();
        assertThat(StageEquipDrop.pick(0L, TABLE, 5L)).as("章号非正数不是一条合法请求").isNull();
    }
}
