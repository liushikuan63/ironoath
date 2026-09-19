package com.ironoath.web.config;

import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.reward.RewardGrantor;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.reward.ServerSeedSource;
import com.ironoath.web.reward.PlayerBag;
import com.ironoath.web.reward.PlayerWallet;
import com.ironoath.web.reward.RewardCompensationStore;
import com.ironoath.web.reward.StoreCompensation;
import com.ironoath.web.store.memory.InMemoryRewardCompensationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：装配通用奖励发放器 —— 把 game-core 的纯逻辑接到真实的存档与背包上。
 * 依赖：Spring Boot、game-core、game-web 的端口适配器。
 *
 * <p>装配方向是单向的：{@link RewardGrantor} 只认 {@code RewardPorts} 里的五个接口，
 * 本类负责把 PlayerRepository / InventoryRepository 适配成 Wallet / Bag。
 * 于是「RewardService 不得直接操作数据库」这条禁止项在编译期就成立 ——
 * 发放器连 PlayerRepository 都看不见。
 *
 * <p>时间源注入 {@link TimeService#serverNow()}：game-core 禁读系统时钟（铁律 5）。
 */
@Configuration
public class RewardBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RewardBeansConfig.class);

    /**
     * 背包端口。既供发放器使用，也供 {@code BagAppService} / {@code CityAppService} 扣道具时使用。
     *
     * <p><b>扣道具必须走这个端口，不能自己拿 {@code InventoryRepository} 改</b>：
     * 背包的每一次改动都必须是「重新读档 → 改 → 带版本落库」。
     * 调用方若自己缓存一份 Inventory 再落库，中间只要夹着一次发奖
     * （{@code PlayerBag.add} 会推进版本号），那份缓存就会以过期版本提交 ——
     * 轻则乐观锁冲突让开箱失败，重则把刚发出去的道具整份覆盖掉，玩家开箱开出一场空。
     * 让「读-改-写」只有一个实现，这类 bug 就无从产生。
     */
    @Bean
    public RewardPorts.Bag playerBag(InventoryRepository inventories, ConfigRegistry configs) {
        return new PlayerBag(inventories, configs);
    }

    /**
     * 资源钱包端口。供发放器使用；扣资源同样应走它，理由同 {@link #playerBag}。
     *
     * <p><b>声明类型是 {@code PlayerWallet}，不是端口接口</b>：Spring 对 {@code @Bean} 的类型预测
     * 看的是方法声明返回类型，而 {@code PlayerCityBattleService} 与 {@code SocialAppService}
     * 都是<b>按具体类</b>注入它的。声明成接口时，这条注入只有在该单例已经被创建、
     * Spring 能直接看到实例真类型之后才匹配得上 —— 等于「谁能拿到 PlayerWallet」取决于 bean 的创建顺序。
     * 本轮给 {@code CounterplayModifiers} 接 SocialAppService 时就把它提前了，上下文当场起不来。
     * 按端口注入的那一侧（{@code MonsterBattleService}）不受影响：它本来就实现了那个端口。
     */
    @Bean
    public PlayerWallet playerWallet(PlayerRepository players) {
        return new PlayerWallet(players);
    }

    /**
     * 通用奖励发放器。
     *
     * <p>{@code extras} 必须<b>注入</b>而不是在这里 new 一个临时实现：
     * B06 已经把武将碎片接到真实背包（{@code HeroBeansConfig#rewardExtras}，带 {@code @Primary}），
     * 这里硬编码 {@code UnsupportedExtras} 会让那个 bean 永远不生效 ——
     * 表现是「武将碎片奖励仍然响亮失败」，而代码里看起来明明已经实现了。
     * 体力（B09）与特权（B15）仍由 Extras 实现抛异常并进补偿队列。
     */
    /**
     * 补偿台账的存储。<b>与邮箱同一套约定</b>：dev/test 用内存版，
     * {@code ironoath.storage=mongo} 时换 {@code MongoRewardCompensationStore}。
     *
     * <p>内存版重启即丢，而这里装的是「玩家该得、当下没拿到」的明细 —— 所以它打 WARN。
     * 判据与 {@code MailBeansConfig#mailStore} 完全一致：<b>不可重算的资产不许只放在进程里</b>。
     */
    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public RewardCompensationStore rewardCompensationStore() {
        LOG.warn("使用内存补偿台账：重启后「发奖失败欠了谁、欠哪几件」会一起消失，"
                + "玩家的投诉将无据可查 —— 生产请设 ironoath.storage=mongo（实现已在 MongoRewardCompensationStore）");
        return new InMemoryRewardCompensationStore();
    }

    /**
     * 补偿队列。<b>提到独立的 {@code @Bean} 而不是在 {@code rewardService} 里内联 new</b>：
     * 商店兑换在「钱已扣、发货抛出实现故障」时也要把这笔债记进<b>同一个</b>队列
     * （见 {@code ShopAppService#deliver}），而内联出来的那份实例别人拿不到 ——
     * 结果会是两处各自记一本谁也看不见谁的账。
     *
     * <p>它此前是 {@code TransientRewardPorts.TransientCompensation}：只在内存与日志里，
     * 且 id 是<b>实例内自增序号</b>。现在这层只做「写台账 + 打日志」，事实交给存储端口，
     * 于是 B04 验收 7 的后半句（补偿队列要有记录）在重启之后仍然成立。
     *
     * <p>声明类型这里用端口而不是具体类：与收口清单 #15 那个坑相反的方向才安全。
     * 那个坑的形状是「{@code @Bean} 声明成端口、<b>消费者按具体类注入</b>」；
     * 本类的消费者（RewardGrantor 与 ShopAppService）全部按端口注入，不存在类型预测问题。
     */
    @Bean
    public RewardPorts.Compensation rewardCompensation(RewardCompensationStore store,
                                                       TimeService timeService) {
        return new StoreCompensation(store, timeService);
    }

    /** 台账的运维出口（{@code GET /ops/reward/compensation} 与 {@code .../resolve}）。 */
    @Bean
    public com.ironoath.web.reward.RewardCompensationAdminService rewardCompensationAdmin(
            RewardCompensationStore store, TimeService timeService) {
        return new com.ironoath.web.reward.RewardCompensationAdminService(store, timeService);
    }

    @Bean
    public RewardService rewardService(RewardPorts.Wallet playerWallet,
                                       RewardPorts.Bag playerBag,
                                       RewardPorts.Mailbox mailbox,
                                       RewardPorts.Extras extras,
                                       RewardPorts.Compensation rewardCompensation,
                                       TimeService timeService) {
        LOG.warn("体力（STAMINA）一类的奖励仍会响亮失败并记进补偿台账，B09 落地前不得对玩家开放；"
                + "欠账本身已经持久化（B04 验收 7），运维从 GET /ops/reward/compensation 看、"
                + "按明细用 POST /ops/mail/send 补发后回到 POST /ops/reward/compensation/resolve 销账。"
                + "溢出邮件已接真邮箱（B12 §2，见 MailBeansConfig#rewardMailbox）。");
        return new RewardGrantor(
                playerWallet,
                playerBag,
                mailbox,
                rewardCompensation,
                extras,
                timeService::serverNow);
    }

    /**
     * 开箱种子源（B04 §4：走服务端 PRNG + seed，结果可复现）。
     *
     * <p><b>用 SecureRandom 而不是 Rng 是有意的，且不违反分层约束</b>：
     * 分层检查禁止的是 game-common/core/battle 三个纯逻辑层碰系统熵 ——
     * 那里的随机必须可复现（同一 seed 同一结果），否则战斗回放与平衡模拟都做不了。
     * 而「种子从哪来」是应用层的信任边界决策，不是玩法逻辑：
     * 种子一旦确定，后续每一次抽取仍然走 game-core 的 {@code Rng}，可复现性完好无损。
     *
     * <p><b>种子绝不能从 requestId、客户端时间或任何请求字段推导</b>：
     * B04 禁止项写的是「不要在客户端本地开箱」，它真正的含义是「客户端无法挑选结果」。
     * 若种子可由请求字段算出，玩家就能离线枚举出哪个 requestId 开得出稀有项，
     * 再拿那个 requestId 发请求 —— 计算确实在服务端，结果却已经被挑过了。
     */
    @Bean
    public ServerSeedSource serverSeedSource() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        return random::nextLong;
    }
}
