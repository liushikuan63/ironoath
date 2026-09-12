package com.ironoath.web.release;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.release.ReleaseGate;
import com.ironoath.core.track.TrackBatcher;

/**
 * 职责：把 global 表的 B16 参数装配成 game-core 的规则对象（埋点批量、发布闸门、性能预算）。
 * 依赖：game-config、game-core。
 *
 * <p><b>这一层存在的唯一理由是铁律 1（数值零硬编码）</b>：game-core 按 B00 分层规则读不到配置表，
 * 所以 {@code TrackBatcher.Rules} 与 {@code ReleaseGate.Rules} 的每一个数字都必须由外层解析好再传进去。
 * 与 {@code BattleRulesAssembler}、{@code SocialRulesAssembler} 是同一种东西。
 *
 * <p><b>本类最容易出的错是单位换算</b>（BattleParamsResolver 踩过一次，SocialRulesAssembler 写在最前面）：
 * {@code TRACK_BATCH_FLUSH_SECONDS} 在表里是秒，而 {@code TrackBatcher.Rules} 要毫秒，<b>必须 ×1000</b>。
 * 漏乘的表现是「攒批窗口只有 10 毫秒」—— 于是每条事件都单独成批，
 * 验收 3 会过（因为确实发了），而禁止项「不要逐条上报」被完全绕过，
 * 线上症状是弱网下埋点把客户端拖垮。
 *
 * <p><b>每次调用都重新装配，不缓存</b>：B16 §5 要求配置表可热更，缓存一份规则会让热更在发布这条路径上失效。
 * 而发布参数恰恰是最需要能热更的 —— 灰度比例从 5% 提到 20% 这件事，等一次发版再做是没有意义的。
 * <b>配置清单的 hash 也不得按版本号缓存</b>：见 {@link #manifest()} 的说明。
 */
@Component
public class ReleaseRulesAssembler {

    private static final long MILLIS_PER_SECOND = 1000L;
    /** 表内容 hash 取前 16 位十六进制（64 bit）。碰撞概率对配置表这个量级可忽略，而全量 64 位会让清单体积翻倍。 */
    private static final int HASH_HEX_LENGTH = 16;

    private final ConfigRegistry configs;

    public ReleaseRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
    }

    // ---------- 埋点批量 ----------

    /** 攒批条数（global.TRACK_BATCH_MAX_SIZE）。原始值，双端同源下发给客户端。 */
    public int trackBatchMaxSize() {
        return (int) configs.longParam("TRACK_BATCH_MAX_SIZE");
    }

    /** 攒批秒数（global.TRACK_BATCH_FLUSH_SECONDS）。原始值，双端同源下发给客户端。 */
    public int trackFlushSeconds() {
        return (int) configs.longParam("TRACK_BATCH_FLUSH_SECONDS");
    }

    /**
     * 埋点攒批规则（B16 §3：10 条或 10 秒触发）。
     *
     * <p><b>秒 → 毫秒的换算只在这里发生一次</b>：客户端拿到的是秒（{@link #trackFlushSeconds()}），
     * 核心层的规则对象要毫秒。若在服务层把毫秒再除回秒下发，同一个换算就存在于两处，
     * 而漏乘那一处的症状是「攒批窗口只有 10 毫秒」—— 每条事件都单独成批，
     * 验收 3 会过（因为确实发了），禁止项「不要逐条上报」被完全绕过。
     */
    public TrackBatcher.Rules trackRules() {
        return new TrackBatcher.Rules(trackBatchMaxSize(), trackFlushSeconds() * MILLIS_PER_SECOND);
    }

    /**
     * 内存版埋点存储的保留条数上限（global.TRACK_STORE_MAX_EVENTS）。
     *
     * <p>这是 dev 与单测的兜底，不是生产口径：生产用 MongoDB 并按时间保留，
     * 且保留期不得短于 {@link #retentionDays()} 的最大值 —— 短于它的话 D30 留存算不出来，
     * 而看板需求文档里写着这个指标。
     */
    public int trackStoreMaxEvents() {
        return (int) configs.longParam("TRACK_STORE_MAX_EVENTS");
    }

    // ---------- 发布闸门 ----------

    /**
     * 版本检查与灰度规则（B16 §5，验收 8）。
     *
     * <p>{@code RELEASE_GRAY_PERCENT} 是 DECIMAL，已由 FixedPointDeserializer 转成定点 long
     * （0.05 → 500），<b>不要再转一次</b>：多转一次不报错，只会让 5% 的灰度变成 500% ——
     * 而 {@code ReleaseGate.Rules} 的构造期校验会把它当成越界拒掉，于是整个服务起不来。
     * 这算是运气好：越界会被拦住，而「多转一次但仍落在区间内」的情况不会有任何提示。
     */
    public ReleaseGate.Rules gateRules() {
        return new ReleaseGate.Rules(
                configs.stringParam("RELEASE_LATEST_VERSION"),
                configs.stringParam("RELEASE_MIN_SUPPORTED_VERSION"),
                configs.fixedParam("RELEASE_GRAY_PERCENT"),
                configs.stringParam("RELEASE_FORCE_UPDATE_NOTICE"));
    }

    /**
     * 当前配置清单（验收 7：改配置不改包生效）。
     *
     * <p><b>hash 由行内容算出，且刻意不把版本号混进 hash 输入</b>：
     * <ul>
     *   <li>混入版本号的话，「升了版本没改内容」会让客户端白下一次完全相同的表 —— 那只是浪费流量</li>
     *   <li>更关键的是反向情形：「改了内容忘了升版本」必须被发现，所以判定依据只能是内容本身</li>
     * </ul>
     *
     * <p><b>不缓存</b>：能安全缓存的键只有内容 hash 本身，而算出它就已经等于算完了。
     * 用 {@code configs.fingerprint()}（版本指纹）做缓存键是最自然的错误 ——
     * 它恰好在那种「改了内容忘了升版本」的情况下不变，于是缓存会把旧 hash 一直供下去，
     * 而症状是「我明明改了表却看不到效果」，那是配置表最难查的一类事故。
     */
    public ReleaseGate.Manifest manifest() {
        List<ReleaseGate.TableMeta> metas = new ArrayList<>();
        for (String name : configs.tableNames()) {
            metas.add(new ReleaseGate.TableMeta(
                    name,
                    String.valueOf(configs.rawTable(name).version()),
                    contentHash(configs.rawTable(name).rows().toString())));
        }
        // 清单版本用全部表的指纹：它只用于「整份跳过」这一条省流量优化，正确性仍由每张表的 hash 保证
        return new ReleaseGate.Manifest(configs.fingerprint(), metas);
    }

    /**
     * 允许热更的内容范围（global.HOT_UPDATE_SCOPE，逗号分隔：CONFIG,GUIDE,ACTIVITY）。
     *
     * <p>这份列表的作用是<b>划边界而不是做过滤</b>：不在列表里的东西（客户端代码、战斗公式、付费价格）
     * 不允许热更，必须走发版。所以它下发给客户端，让客户端知道自己能拉什么。
     */
    public List<String> hotUpdateScope() {
        String raw = configs.stringParam("HOT_UPDATE_SCOPE");
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("HOT_UPDATE_SCOPE 为空：那等于宣布本项目不支持热更，"
                    + "而 B16 §5 要求配置表、引导脚本、活动三类都必须能热更");
        }
        return List.copyOf(out);
    }

    // ---------- 性能预算 ----------

    /**
     * B16 §1 的性能预算。<b>这一份是「服务端能自检的那部分」</b>：
     * 首包体积由 CI 卡口读同一张表校验（scripts/check-package-size.sh），
     * FPS / 首屏 / GC 只能在真机与生产环境测，配在这里是为了让上线检查清单与配置表同源，
     * 而不是在清单文档里另抄一份数字 —— 抄两份的结果是改了预算而清单还是旧值。
     */
    public PerfBudget perfBudget() {
        return new PerfBudget(
                configs.longParam("PERF_FIRST_PACKAGE_MAX_BYTES"),
                configs.longParam("PERF_API_P99_MAX_MS"),
                configs.longParam("PERF_BATTLE_SETTLE_P99_MAX_MS"),
                configs.longParam("PERF_PAYLOAD_MAX_BYTES"),
                configs.longParam("PERF_MEMORY_PEAK_MAX_MB"),
                configs.longParam("PERF_MIN_FPS"),
                configs.longParam("PERF_FIRST_SCREEN_MAX_MS"),
                configs.longParam("PERF_FULL_GC_MAX_PER_HOUR"),
                configs.longParam("PERF_FULL_GC_PAUSE_MAX_MS"));
    }

    /** 看板要算的留存天数（global.DASHBOARD_RETENTION_DAYS）。 */
    public List<Integer> retentionDays() {
        List<Integer> out = new ArrayList<>();
        for (String part : configs.stringParam("DASHBOARD_RETENTION_DAYS").split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(Integer.parseInt(trimmed));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("DASHBOARD_RETENTION_DAYS 为空：看板将算不出任何留存口径");
        }
        return List.copyOf(out);
    }

    private static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HASH_HEX_LENGTH);
            for (int i = 0; i < bytes.length && sb.length() < HASH_HEX_LENGTH; i++) {
                sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(bytes[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境本身已经不可信
            throw new IllegalStateException("SHA-256 不可用，无法计算配置表指纹", e);
        }
    }

    /**
     * B16 §1 的性能预算（全部来自 global 表，无一处硬编码）。
     *
     * @param firstPackageMaxBytes   首包上限（微信硬限制 4MB）
     * @param apiP99MaxMs            服务端接口 P99 上限（不含战斗结算）
     * @param battleSettleP99MaxMs   战斗结算 P99 上限
     * @param payloadMaxBytes        单次请求 payload 上限
     * @param memoryPeakMaxMb        客户端内存峰值上限
     * @param minFps                 战斗 / 地图场景帧率下限
     * @param firstScreenMaxMs       首屏可交互上限
     * @param fullGcMaxPerHour       Full GC 频率上限
     * @param fullGcPauseMaxMs       Full GC 单次停顿上限
     */
    public record PerfBudget(long firstPackageMaxBytes, long apiP99MaxMs, long battleSettleP99MaxMs,
                             long payloadMaxBytes, long memoryPeakMaxMb, long minFps,
                             long firstScreenMaxMs, long fullGcMaxPerHour, long fullGcPauseMaxMs) {
    }
}
