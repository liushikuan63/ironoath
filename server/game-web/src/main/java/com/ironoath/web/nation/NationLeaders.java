package com.ironoath.web.nation;

import org.springframework.stereotype.Component;

import com.ironoath.core.nation.Nation;
import com.ironoath.web.store.memory.InMemorySocialStore;

/**
 * 职责：把「联盟 → 盟主」这份查询注入 {@link Nation}，使议员席（B13 §2「每盟主 1 席」）
 *       按<b>当下的</b>盟主重算。依赖：社交存储（盟主住在联盟那一侧）、{@link Nation}。
 *
 * <p><b>议员是国家里唯一的派生席位</b>，这一点决定了它的读法：其它官职是"有人任命出来的"，
 * 而议员是"这个人本来就是盟主，所以他天然占一席"。国家不持有联盟对象（分层，见 {@code Nation}
 * 类注释第 4 条冲突规则），所以这份派生必须由外层注入一次查询来完成 ——
 * {@code Nation.Snapshot} 的注释里那句「{@code allianceLeaderLookup} 是注入的函数，
 * 重建后由外层再 bind」说的就是这件事。
 *
 * <p><b>为什么单独一个类，而不是两个服务各写一行 bind</b>：bind 这件事的漏掉方式很阴 ——
 * 少 bind 的那一份副本在被写回时，{@code representativesChanged()} 会因为查不到盟主
 * 而<b>把整张议员表算成空</b>。表现不是报错，而是"某个国家的服务层一改，别的主盟的议员席全没了"。
 * 两个调用点各抄一份注入 lambda，早晚一份改了一份没改，所以收在这里。
 *
 * <p><b>每次读都要 bind，不能只 bind 一次</b>：盟主会在两次读之间转让（B10 §2 的转让盟主），
 * 而 {@code Nation} 是可变对象、两套存储读出来都是副本。bind 内部会立刻重算一次，
 * 所以"读到的议员"永远等于"按当下联盟状态算出的议员"，存档里那份旧表只是缓存。
 *
 * <p><b>查不到联盟就当作没有盟主</b>：社交侧还没有存储端口（收口清单 #16 的最后一项），
 * 进程重启后联盟对象会暂时不在，那一轮议员会被算成空 —— 宁可显示"议员暂时空着"，
 * 也不能把一个已经不存在的盟主留在席位上。等社交进 Mongo，这一条自然消失。
 */
@Component
public class NationLeaders {

    private final InMemorySocialStore social;
    /** 合规红线的唯一判定入口（B13 §2、B16 §七 4：Bot 不得担任任何国家官职）。 */
    private final com.ironoath.web.bot.BotRegistry bots;

    public NationLeaders(InMemorySocialStore social, com.ironoath.web.bot.BotRegistry bots) {
        this.social = social;
        this.bots = bots;
    }

    /**
     * 注入盟主查询并立刻重算议员席。
     *
     * <p><b>Bot 盟主不占这一席</b>：议员是 {@code Nation.Office} 里的一档官职，而
     * 「Bot 不得担任任何国家官职」是 B13 §2 的合规红线。任命那条路径由
     * {@code BotRegistry.requireMayHoldOffice} 挡住，而议员是<b>派生</b>的、没人任命它，
     * 所以红线必须落在这份查询上 —— 这也是判定写成"返回 null"而不是抛错的原因：
     * 查看国家的人不该因为某个联盟的盟主是 Bot 就吃一个错。
     *
     * <p><b>口径只有一个家</b>：问的是 {@code bots.mayHoldOffice(..., "NATION", false, true)} ——
     * "议员是国家官职（isOffice），不是国主位（isLeader=false）"，答案由
     * {@code BotTuning.mayHoldOffice} 的 §六 表给出；真人一律开放，所以这里不写任何身份分支。
     *
     * <p><b>入籍本身不受这条影响</b>：入籍是成员关系而不是官职，Bot 联盟照样能加入国家
     * （B11 要求 Bot 走与真人完全相同的 service 层），只是不占议员席。
     *
     * <p>判定走注册表而不是在这里问身份，是 {@code check-no-bot-privilege.sh} 的形状要求：
     * 生产代码里不许出现那个字段或分支，只许委托。
     *
     * @return 同一个 nation（便于在读取表达式末尾接一行），不是副本
     */
    public Nation bind(Nation nation) {
        nation.bindAllianceLeaderLookup(
                allianceId -> social.allianceById(allianceId)
                        .map(a -> bots.mayHoldOffice(a.leaderId(), "NATION", false, true)
                                ? a.leaderId() : null)
                        .orElse(null));
        return nation;
    }
}
