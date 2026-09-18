package com.ironoath.web.service;

import java.util.Comparator;
import java.util.List;

import com.ironoath.config.cfg.EquipCfg;

/**
 * 职责：通章送哪一件装备（B20 装备获取来源 (b)：2026-09-18 用户裁决）。
 * 依赖：`equip` 表与一个阈值参数，纯函数、不起容器。
 *
 * <p><b>为什么是"章号"的函数而不是逐关填表</b>：50 关逐关挑装备等于让产品替装备表做 50 次选择，
 * 而且每加一章都要再挑一遍；这条规则只留一个旋钮（第几章起给 SR 装），
 * 表里加一行装备也自动进入轮转。**概率公示一个字都不动**（军资箱那条 `chest_drop` 不碰）。
 *
 * <p><b>轮转为什么按章号取模</b>：同一档里轮着发，才不会十章下来人人只拿到那第一件；
 * 取模而不是随机 —— 随机会让"同一章的两个玩家拿到不同装备"，而通章奖励是**确定性的进度奖励**，
 * 不是抽奖（玩家之间可以互相告知"第三章给什么"）。
 *
 * <p><b>返回 null 的两个口子</b>：表里没有该稀有度的行、或表是空的。调用方要**记日志并跳过**，
 * 而不是发一件乱七八糟的东西 —— 配置缺行的症状必须是"这一章没发装备 + 日志里说得清"，
 * 不是"发了一件不属于这个档位的"。
 */
public final class StageEquipDrop {


    private StageEquipDrop() {
    }

    /**
     * 选一件通章奖励装备。
     *
     * @param chapterNo     章号（`chapter.chapterNo`），从 1 起
     * @param equipRows     全表（`configs.all(EquipCfg.class)`）
     * @param srFromChapter 第几章起给 SR 装；来自 `global.STAGE_EQUIP_SR_FROM_CHAPTER`
     * @return 选中的行；表里没有对应稀有度时返回 null
     */
    public static EquipCfg pick(long chapterNo, List<EquipCfg> equipRows, long srFromChapter) {
        if (equipRows == null || equipRows.isEmpty() || chapterNo < 1L) {
            return null;
        }
        // rarity 是枚举（EquipCfg.Rarity），不是字符串：写成 "N".equals(row.rarity()) 会永远为 false
        // —— 编译通过、静默不发装备，正是本类用例要抓的那种形状
        EquipCfg.Rarity rarity = chapterNo >= srFromChapter ? EquipCfg.Rarity.SR : EquipCfg.Rarity.N;
        List<EquipCfg> candidates = equipRows.stream()
                .filter(row -> rarity == row.rarity())
                .sorted(Comparator.comparing(EquipCfg::id))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        // 按章号在候选里轮转：确定、可复跑，且同一档里不会十章都发同一件
        int index = (int) ((chapterNo - 1L) % candidates.size());
        return candidates.get(index);
    }
}
