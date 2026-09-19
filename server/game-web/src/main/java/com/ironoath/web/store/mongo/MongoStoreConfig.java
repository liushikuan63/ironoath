package com.ironoath.web.store.mongo;

import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.config.GameProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 职责：MongoDB 存储装配 + 索引初始化 —— {@code ironoath.storage=mongo} 时生效。
 * 依赖：Spring Data MongoDB。
 *
 * <p>索引必须在启动时确保存在，原因有二：
 * <ol>
 *   <li>{@code deviceId} 唯一索引是「同设备只建一个号」的<b>唯一</b>保证。
 *       索引缺失时 {@code insertIfAbsent} 会静默退化成「永远插入成功」，
 *       一个设备能建出无数个号 —— 这是刷资源的直接入口。</li>
 *   <li>{@code expireAt} 的 TTL 索引负责回收幂等键。没有它，request_id 集合会无限膨胀。</li>
 * </ol>
 * 索引定义为什么抽到 {@link MongoIndexes}：契约测试（MongoPlayerStoreContractTest）必须确保
 * <b>同一份</b>索引 —— "并发插入只有一个赢家"这条断言成立的前提就是 deviceId 唯一索引，
 * 测试里自己抄一遍 ensureIndex 等于验了个复印件，生产真少建了索引也不会红。
 */
@Configuration
@ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MONGO)
public class MongoStoreConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MongoStoreConfig.class);

    @Bean
    public PlayerRepository playerRepository(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 玩家存储");
        return new MongoPlayerStore(mongo);
    }

    /**
     * 城建仓储。收口清单 #16 里"mongo 模式下没有 bean、上下文直接起不来"的两个存储之一，
     * 这是第一个补上的：语义等价由 {@code CityStoreContractTest}（内存）与
     * {@code MongoCityStoreContractTest}（真实 Mongo）跑同一份契约保证。
     */
    @Bean
    public com.ironoath.core.city.CityRepository cityRepository(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 城建存储");
        return new MongoCityStore(mongo);
    }

    /**
     * 背包仓储。与城建同为 #16 记的"mongo 模式下没有 bean、上下文直接起不来"那两个存储之一，
     * 两个都补上之后，{@code -Dspring-boot.run.profiles=dev,mongo} 才第一次有可能起得来。
     */
    @Bean
    public com.ironoath.core.bag.InventoryRepository inventoryRepository(
            MongoTemplate mongo, com.ironoath.config.ConfigRegistry configs) {
        LOG.info("使用 MongoDB 背包存储");
        return new MongoInventoryStore(mongo, configs);
    }

    /** 武将存档（养成 + 编队）。契约见 {@code MongoHeroStoreContractTest}。 */
    @Bean
    public com.ironoath.core.hero.HeroRepository heroRepository(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 武将存储");
        return new MongoHeroStore(mongo);
    }

    /** 军队存档（兵力 + 训练队列 + 伤兵 + 治疗进度）。契约见 {@code MongoArmyStoreContractTest}。 */
    @Bean
    public com.ironoath.core.army.ArmyRepository armyRepository(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 军队存储");
        return new MongoArmyStore(mongo);
    }

    /** 抽卡保底进度（按卡池分桶）。#39 之后仍欠的两类里，这两类都直接影响合规口径。 */
    @Bean
    public com.ironoath.core.gacha.GachaStateRepository gachaStateRepository(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 抽卡保底进度：重启不再清零，公示的「保底计数不因版本更新而清零」从此可兑现");
        return new MongoGachaStateStore(mongo);
    }

    /** 抽卡日志（合规凭证）。内存版重启即丢，而 B06 §6 要求保留 90 天且监管会查。 */
    @Bean
    public com.ironoath.core.gacha.GachaLogStore gachaLogStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 抽卡日志（一批一文档，保证一次十连要么全写要么全不写）");
        return new MongoGachaLogStore(mongo);
    }

    /**
     * 行军存储。chunkSize 与内存版一样从 {@code WORLD_CHUNK_SIZE} 读：
     * 两侧算出的 chunk 键必须一致，否则 viewport 增量下发在生产上会静默漏掉所有行军实体
     * （地图上看不到任何在途队伍，而没有任何一处会报错）。
     */
    @Bean
    public com.ironoath.core.march.MarchRepository marchRepository(MongoTemplate mongo,
            com.ironoath.config.ConfigRegistry configs) {
        LOG.info("使用 MongoDB 行军存储（B07 验收 1：重启后按真实剩余时间继续）");
        return new MongoMarchStore(mongo, (int) configs.longParam("WORLD_CHUNK_SIZE"));
    }

    /**
     * 关卡进度。契约见 {@code MongoStageProgressStoreContractTest}。
     * 这一档丢不起的不只是星级：首通奖发不发的唯一判据就在这份进度里，
     * 重启即丢等于全服首通奖重发一遍。
     */
    @Bean
    public com.ironoath.core.stage.StageProgressRepository stageProgressRepository(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 关卡进度存储（首通判据不再随进程消失）");
        return new MongoStageProgressStore(mongo);
    }

    /**
     * 战报。内存版重启即清空列表且不报错，而 {@code BattlePlayback} 放的就是这份档。
     * 幂等（同 reportId 不覆盖）、倒序 + reportId 同刻定序、过期边界这三条语义
     * 由 {@code BattleReportStoreEquivalenceTest} 在内存与真实 Mongo 上跑同一组断言钉住。
     */
    @Bean
    public com.ironoath.web.battle.BattleReportStore battleReportStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 战报存储（重启后回放还在，列表不会静默清空）");
        return new MongoBattleReportStore(mongo);
    }

    /**
     * 国家。契约见 {@code NationStoreEquivalenceTest}（内存与真实 Mongo 跑同一组断言）。
     * 需要 {@code NationRulesAssembler}：规则不进快照（进了就等于把一次热更冻进存档），
     * 而两套实现的读都返回副本，所以重建一个能用的国家必须现取规则。
     */
    @Bean
    public com.ironoath.web.nation.NationStore nationStore(MongoTemplate mongo,
            com.ironoath.web.nation.NationRulesAssembler rules) {
        LOG.info("使用 MongoDB 国家存储（官职、外交与国库账目不再随进程消失）");
        return new MongoNationStore(mongo, rules);
    }

    /**
     * 赛季账本。契约见 {@code SeasonLedgerStoreEquivalenceTest}。
     * 这一档防的不是"数据丢了不好看的"，而是<b>重启之后再点一次结算就把金币重复发出去</b> ——
     * 账本就是"这一季已经付过"的唯一持久凭据。
     */
    @Bean
    public com.ironoath.web.season.SeasonLedgerStore seasonLedgerStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 赛季账本（已发奖的凭据不再随进程消失）");
        return new MongoSeasonLedger(mongo);
    }

    /**
     * 战令进度（B24 块②）。契约见 {@code BattlePassStoreEquivalenceTest}。
     * 内存版重启即空，而空进度意味着<b>已领过的档位重新变成可领</b> —— 那一发就是重复发奖。
     */
    @Bean
    public com.ironoath.web.battlepass.BattlePassStore battlePassStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 战令进度（打过的分与领过的档位不再随进程消失）");
        return new MongoBattlePassStore(mongo);
    }

    /**
     * 赛季榜与快照。契约见 {@code SeasonBoardStoreEquivalenceTest}。
     * 这一档防的是「按一张冷榜结算」：快照不可重拍（申诉依据），而账本会把算错的名次记成
     * "已经付过" ⇒ 本该拿奖的人从此拿不到。落库之后，同样的输入重跑一次才是同样的结果。
     */
    @Bean
    public com.ironoath.web.season.SeasonBoardStore seasonBoardStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 赛季榜与快照（结算依据不再随进程消失，重跑同输入同结果）");
        return new MongoSeasonBoardStore(mongo);
    }

    /**
     * 任务进度。契约见 {@code QuestProgressStoreEquivalenceTest}。
     * 这一档防的是「进度丢一次就永久少一格」：累加型目标（累计训练 20 个兵）无法从当前状态反推 ——
     * 兵可能已经战死，所以它只能在事件发生那一刻记下来。
     */
    @Bean
    public com.ironoath.web.quest.QuestProgressStore questProgressStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 任务进度（累计进度不再随进程消失）");
        return new MongoQuestProgressStore(mongo);
    }

    /**
     * 邮件（B12 §2）。这一档防的是「玩家该得的东西随进程消失」：
     * 邮件里装着<b>没领走的附件</b>（发奖溢出的补发、运营补偿），内存版重启就是丢玩家资产，
     * 而它没有任何重算入口 —— 与任务进度不同，进度至少还能从当前状态反推一部分。
     */
    @Bean
    public com.ironoath.web.mail.MailStore mailStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 邮箱（未领附件不再随进程消失，过期按 MAIL_RETENTION_DAYS 惰性清理）");
        return new MongoMailStore(mongo);
    }

    /**
     * 活动进度（B17）。这一档防的是「连续签到天数与活动进度随进程消失」——
     * 两类都无法从当前状态反推（日子过去了、怪已经死了），所以只能在事件发生那一刻记下来。
     * 契约见 {@code ActivityStoreEquivalenceTest}。
     */
    @Bean
    public com.ironoath.web.activity.ActivityProgressStore activityProgressStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 活动进度（连续签到与每轮进度不再随进程消失）");
        return new MongoActivityProgressStore(mongo);
    }

    /**
     * 支付订单。这一类是全部存储里优先级最高的：「已收款未发货」是一笔负债，
     * 内存版重启即消失，而消失的那笔钱没有任何追查入口（收口清单 #16 与 #49，
     * 另见 上线检查清单 §四 2）。
     * 与内存登记簿跑同一份 {@code PayOrderStoreEquivalenceTest}，
     * 侧重点不是「能读能写」而是<b>写完必须重新读一遍</b>——
     * 内存版返回活对象，漏一次 save 不会红；Mongo 版会。
     */
    @Bean
    public com.ironoath.core.pay.PayOrderStore payOrderStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 支付订单存储（重启后补单队列与未发货负债都还在）");
        return new MongoPayOrderStore(mongo);
    }

    /**
     * 世界状态（城位置、消耗格、chunk 版本、迷雾、侦查报告）。契约见 {@code WorldStoreEquivalenceTest}。
     *
     * <p>{@code chunkSize} 与内存版一样从 {@code WORLD_CHUNK_SIZE} 读：两侧算出的 chunk 键与
     * 坐标存储键必须逐字节相同，否则增量下发会静默漏掉整块（地图上一片空白而没有任何一处报错）。
     */
    @Bean
    public com.ironoath.core.world.WorldRepository worldRepository(MongoTemplate mongo,
            com.ironoath.core.march.MarchRepository marches,
            com.ironoath.config.ConfigRegistry configs) {
        LOG.info("使用 MongoDB 世界存储（城位置与迷雾不再随进程消失，消耗掉的格子不会复活）");
        return new MongoWorldStore(mongo, marches, (int) configs.longParam("WORLD_CHUNK_SIZE"));
    }

    /**
     * 行军到期队列。它不是"多一份缓存"：{@code MarchAppService} 的到期推进完全依赖
     * {@code dueBefore} 返回的 id，队列一旦随进程消失，重启后所有在途行军会停在半路永不推进
     * （B07 验收 1 要求杀进程重进后按真实剩余时间继续）。写放大很小（每支行军一次 upsert），
     * 但缺了它等于 mongo 模式下行军功能只剩半条命。语义等价见 {@code MarchDueQueueEquivalenceTest}。
     */
    @Bean
    public com.ironoath.core.march.MarchDueQueue marchDueQueue(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 行军到期队列（重启后在途队伍仍会被到期扫描推进）");
        return new MongoMarchDueQueue(mongo);
    }
    /**
     * 社交存储。这是 {@link com.ironoath.web.config.MongoStorageGuard} 清单上的最后一类
     * "无条件装配的内存实现"（收口清单 #16）：小队、联盟、入盟申请、帮助请求、社交事件、聊天记录、集结。
     * 语义等价由 {@code SocialStoreEquivalenceTest} 在内存与真实 Mongo 两侧跑同一组用例保证。
     * 规则（Squad/Alliance 的等级表）不进档，重建时从 {@code SocialRulesAssembler} 现取。
     */
    @Bean
    public com.ironoath.web.social.SocialStore socialStore(MongoTemplate mongo,
            com.ironoath.web.social.SocialRulesAssembler rules) {
        LOG.info("使用 MongoDB 社交存储（小队、联盟、聊天、互助与集结不再随进程消失）");
        return new MongoSocialStore(mongo, rules);
    }
    /**
     * 埋点与崩溃上报。契约见 {@code TrackStoreEquivalenceTest}。
     * 这一档缺了不会有人报错，只是<b>上线第一天没人看得懂玩家卡在哪一步走</b> ——
     * 而 D1/D3/D7/D30 留存与卡点流失率全部依赖它。
     */
    @Bean
    public com.ironoath.web.ops.TrackEventStore trackEventStore(MongoTemplate mongo) {
        LOG.info("使用 MongoDB 埋点存储（按时间保留，不再有条数上限；崩溃记录以 traceId 幂等）");
        return new MongoTrackStore(mongo);
    }

    @Bean
    public IdempotencyStore idempotencyStore(MongoTemplate mongo) {
        return new MongoIdempotencyStore(mongo);
    }

    /** 启动后确保索引存在。已存在则幂等，不会重复创建。 */
    @Bean
    public ApplicationRunner mongoIndexInitializer(MongoTemplate mongo) {
        return args -> MongoIndexes.ensure(mongo);
    }
}
