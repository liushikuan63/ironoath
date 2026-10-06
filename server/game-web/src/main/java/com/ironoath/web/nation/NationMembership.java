package com.ironoath.web.nation;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ironoath.core.nation.Nation;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：把「这个玩家属于哪个国家」这一跳收在一处（联盟 ⊂ 国家，国籍跟随联盟）。
 * 依赖：{@link SocialStore}（玩家 → 联盟）、{@link NationStore}（联盟 → 国家）。
 *
 * <p><b>为什么单独一个类而不是各处自己写两跳</b>：这一跳已经有三个读者 ——
 * 国战读视图（{@code WarAppService}）、击杀归属（{@code BattleReportService}）、
 * 国家榜投影（{@code RankBoardService} 走的是批量口 {@code nationsByAlliance}，形状不同故不共用）。
 * 前两跳如果各写一遍，最先分叉的是<b>「没有联盟」与「有联盟但没入籍」这两种缺席</b>怎么处理：
 * 一处回 empty、另一处抛「你不在任何联盟中」，症状是同一个人在一个面板看得到国家、在另一个面板看不见。
 *
 * <p><b>返回的是副本且不做 {@code NationLeaders.bind}</b>：议员席那一项需要注入盟主查询
 * （{@code NationAppService} 那边才做），而国战与击杀归属只读官职、外交与成员表 ——
 * 在这里 bind 等于给每个调用方都付一次联盟查询的代价，还让人误以为这份对象能算议员席。
 */
@Component
public class NationMembership {

    private final SocialStore social;
    private final NationStore nations;

    public NationMembership(SocialStore social, NationStore nations) {
        this.social = social;
        this.nations = nations;
    }

    /**
     * 这个玩家当前所属的国家。
     *
     * @return 没有联盟、或联盟没入籍、或玩家 id 为空时都是 {@code empty} ——
     *         <b>这三种缺席对调用方是同一件事</b>（「他不在任何国家里」），
     *         分开返回会让上层以为存在"有联盟但查不到"这种需要单独处理的状态
     */
    public Optional<Nation> ofPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Optional.empty();
        }
        return social.allianceOf(playerId).flatMap(alliance -> nations.findByAlliance(alliance.id()));
    }

    /** 只有国家 id 的版本：给"不需要整份聚合"的调用方（击杀归属只要 id，读整档纯属浪费一次反序列化）。 */
    public String nationIdOf(String playerId) {
        return ofPlayer(playerId).map(Nation::id).orElse(null);
    }
}
