package com.ironoath.web.pay;

import java.util.ArrayList;
import java.util.List;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigException;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.config.cfg.ProductRewardCfg;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;
import com.ironoath.core.player.PlayerPaid;
import com.ironoath.web.dto.generated.PayRewardItem;

/**
 * 职责：读 B19 的两张付费表（{@code pay_product} 商品结构 + {@code product_reward} 发货内容），
 * 并把它们翻译成领域侧的东西：价格、奖励清单、当前生效的权益。
 * 依赖：{@link ConfigRegistry}。
 *
 * <p><b>为什么全项目的付费商品读法集中在这一个类</b>：商品表与奖励表被三处要用 ——
 * 下单（价格与选将校验）、发货（发什么）、领取（日包与基金档位）。各读各的，
 * 就会有一处按 {@code priceCentsParam} 取价、另一处直接写死 {@code PRODUCT_MONTHLY_CARD_CENTS}，
 * 而这两处的分叉表现是"界面显示新价、扣款扣旧价"。
 * （这不再是纸面担忧：B19-S1 之前 {@code PayAppService.priceOf} 里那份 productId→参数名 的
 * switch 就是第二个家，本批次删掉了它。）
 *
 * <p><b>奖励类型在这一个类里跨两次桥</b>：{@code ProductRewardCfg.RewardType} 是配置表自己的枚举
 * （只有 RESOURCE / ITEM，表里写别的会被加载期校验拦住），而领域侧是 {@code core.reward.RewardType}
 * （六个值）。两份都是编译期安全的，转换收在这里，就有"新加一列取值忘了同步"这个风险 ——
 * 因此 {@code fromConfigured} 的 default 分支必须抛，且这两次转换由
 * {@code PayContractParityTest} 逐值钉住。
 */
public final class PaidProducts {

    private final ConfigRegistry configs;

    public PaidProducts(ConfigRegistry configs) {
        if (configs == null) {
            throw new IllegalArgumentException("ConfigRegistry 不得为 null");
        }
        this.configs = configs;
    }

    // ---------- 商品 ----------

    /** 按 id 取商品行；不存在就是下架或未定义 —— 与 {@code PAY_PRODUCT_OFFLINE} 同一条语义。 */
    public PayProductCfg require(String productId) {
        if (productId == null || productId.isBlank()) {
            throw new BizException(ErrorCode.PAY_PRODUCT_OFFLINE, "productId 不得为空");
        }
        try {
            return configs.get(PayProductCfg.class, productId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.PAY_PRODUCT_OFFLINE,
                    "商品已下架或不存在: " + productId + "（pay_product 表里没有这一行）");
        }
    }

    /** 是否存在这个商品（下单前的软判定用，不抛异常）。 */
    public boolean known(String productId) {
        if (productId == null || productId.isBlank()) {
            return false;
        }
        for (PayProductCfg row : configs.all(PayProductCfg.class)) {
            if (row.id().equals(productId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 商品单价（分）。
     *
     * <p><b>表里存的是参数名而不是数字</b>（{@code priceCentsParam}）：价格早已住在 global 的
     * PRODUCT_*_CENTS 里，本表再存一份就是同一个价两个家（designNote 第 1 条）。
     */
    public long priceCents(PayProductCfg product) {
        String param = product.priceCentsParam();
        if (param == null || param.isBlank() || !configs.hasParam(param)) {
            throw new BizException(ErrorCode.PAY_PRODUCT_OFFLINE,
                    "商品 " + product.id() + " 的价格参数 " + param + " 在 global 表里不存在："
                            + "宁可判成下架，也不能按 0 分或旧价扣钱");
        }
        return configs.longParam(param);
    }

    /** 该商品的「三选一」候选（表里是一份逗号分隔的武将 id，与 quest_main_01 同一批）。 */
    public List<String> heroChoices(PayProductCfg product) {
        String raw = product.heroChoices();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 按类别取那一行的商品（月卡、基金）。
     *
     * <p><b>不写死行 id</b>：领取路径要知道"日包挂在哪个商品下"，写死 {@code "monthly_card"}
     * 就是代码里有一份表内容 —— 改表 id 之后代码仍然按老 id 找，症状是"玩家买了月卡却领不到日包"，
     * 而不是一个能指向真问题的错。
     *
     * <p><b>没有、或有不止一行都要响</b>：两行 MONTHLY_CARD 时"日包是哪三行"没有答案，
     * 静默取第一行等于把选择权交给表的排序。
     */
    public PayProductCfg requireKind(PayProductCfg.Kind kind) {
        List<PayProductCfg> matches = new ArrayList<>();
        for (PayProductCfg row : configs.all(PayProductCfg.class)) {
            if (row.kind() == kind) {
                matches.add(row);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("pay_product 表里没有任何 kind=" + kind
                    + " 的行：这一族权益没有商品可挂，领取路径无从计算");
        }
        if (matches.size() > 1) {
            List<String> ids = matches.stream().map(PayProductCfg::id).toList();
            throw new IllegalStateException("pay_product 里 kind=" + kind + " 的行不止一行（" + ids
                    + "）：日包与档位该挂哪一件商品没有答案，不能靠排序挑");
        }
        return matches.get(0);
    }

    /**
     * 这个账号是否已经买过这类一次性商品。返回一句话说明为什么不能再买，可买时返回 null。
     *
     * <p><b>判"能不能买"只在这一个地方</b>：下单时要拦（不然玩家付第二笔钱），发货时也要拦
     * （并发下第二笔回调先到了）。抄两份的后果是两处规则一先一后失效 ——
     * 先失效的那一处会安静地收第二笔钱。
     *
     * <p>月卡不在其列：§五②c 裁的是"未到期再买直接 +30 天，不设上限"，续期是正常业务。
     */
    public String alreadyOwned(PlayerPaid paid, PayProductCfg product) {
        if (paid == null) {
            return null;
        }
        return switch (product.kind()) {
            case FIRST_CHARGE -> paid.firstCharged()
                    ? "本账号已经完成过首充，这一档是一次性的（不是下架，是这一档只送一次）" : null;
            case GROWTH_FUND -> paid.fundPurchased()
                    ? "本账号已经买过成长基金，返还档位是跟着账号的，再买一份不会多出六档" : null;
            case MONTHLY_CARD -> null;
        };
    }

    /** 在售商品（价格表下发用）。价格参数缺失的商品一并跳过 —— 它本来就买不了。 */
    public List<PriceEntry> onSale() {
        List<PriceEntry> out = new ArrayList<>();
        for (PayProductCfg row : configs.all(PayProductCfg.class)) {
            String param = row.priceCentsParam();
            if (param == null || param.isBlank() || !configs.hasParam(param)) {
                continue;
            }
            out.add(new PriceEntry(row.id(), configs.longParam(param)));
        }
        return List.copyOf(out);
    }

    /** 价格表的一行（还没决定币种与划线价，那是 {@code PayAppService} 的事）。 */
    public record PriceEntry(String productId, long cents) {
    }

    // ---------- 发货内容 ----------

    /** 某商品的全部奖励行，按 {@code requireMainLevel} 升序（null 门槛排最前，即"没有门槛"）。 */
    public List<ProductRewardCfg> rewardRows(String productId) {
        List<ProductRewardCfg> out = new ArrayList<>();
        for (ProductRewardCfg row : configs.all(ProductRewardCfg.class)) {
            if (productId.equals(row.productId())) {
                out.add(row);
            }
        }
        out.sort((a, b) -> Long.compare(levelOf(a), levelOf(b)));
        return List.copyOf(out);
    }

    /** 某商品的基金档位（带 {@code requireMainLevel} 的那些行）。 */
    public List<ProductRewardCfg> fundTiers(String productId) {
        List<ProductRewardCfg> out = new ArrayList<>();
        for (ProductRewardCfg row : rewardRows(productId)) {
            if (row.requireMainLevel() != null) {
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    /** 没有等级门槛的奖励行：首充的金币、月卡的日包都从这里取。 */
    public List<ProductRewardCfg> alwaysRows(String productId) {
        List<ProductRewardCfg> out = new ArrayList<>();
        for (ProductRewardCfg row : rewardRows(productId)) {
            if (row.requireMainLevel() == null) {
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    /** 一档奖励行 × 份数 → 领域奖励。 */
    public List<RewardItem> toDomainRewards(List<ProductRewardCfg> rows, long multiplier) {
        if (multiplier < 1L) {
            throw new IllegalArgumentException("份数倍率必须 >= 1，实际=" + multiplier);
        }
        List<RewardItem> out = new ArrayList<>(rows.size());
        for (ProductRewardCfg row : rows) {
            out.add(new RewardItem(fromConfigured(row.rewardType()), row.rewardId(),
                    row.count() * multiplier));
        }
        return List.copyOf(out);
    }

    /** 领域奖励 → 协议奖励（下发给客户端对账的那一份）。 */
    public static List<PayRewardItem> toProtocol(List<RewardItem> items) {
        List<PayRewardItem> out = new ArrayList<>(items.size());
        for (RewardItem item : items) {
            out.add(new PayRewardItem(item.type().name(), item.id(), item.count()));
        }
        return List.copyOf(out);
    }

    /** 协议/订单里的奖励行 → 领域奖励（查单端点回放历史订单时用）。 */
    public static List<RewardItem> fromProtocolRows(List<com.ironoath.core.pay.PayOrder.RewardRow> rows) {
        List<RewardItem> out = new ArrayList<>(rows.size());
        for (com.ironoath.core.pay.PayOrder.RewardRow row : rows) {
            out.add(new RewardItem(RewardType.valueOf(row.type()), row.id(), row.count()));
        }
        return List.copyOf(out);
    }

    /** 领域奖励 → 订单里存的那份（与协议那份同值，只是类型不同：core 不能看见 web 的 DTO）。 */
    public static List<com.ironoath.core.pay.PayOrder.RewardRow> toOrderRows(List<RewardItem> items) {
        List<com.ironoath.core.pay.PayOrder.RewardRow> out = new ArrayList<>(items.size());
        for (RewardItem item : items) {
            out.add(new com.ironoath.core.pay.PayOrder.RewardRow(item.type().name(), item.id(),
                    item.count()));
        }
        return List.copyOf(out);
    }

    /**
     * 配置表枚举 → 领域枚举。<b>逐值写出而不是 valueOf(name())</b>：
     * 表将来放开 PRIVILEGE 之类的取值时，这里必须有人显式看过一眼（default 直接抛），
     * 静默 valueOf 会让一个语义完全不同的类型进到发奖链路里。
     */
    public static RewardType fromConfigured(ProductRewardCfg.RewardType type) {
        return switch (type) {
            case RESOURCE -> RewardType.RESOURCE;
            case ITEM -> RewardType.ITEM;
        };
    }

    /** 领域枚举 → 配置表枚举（一致性测试与将来的表扩展都会用到；不在表取值范围内的必须抛）。 */
    public static ProductRewardCfg.RewardType toConfigured(RewardType type) {
        return switch (type) {
            case RESOURCE -> ProductRewardCfg.RewardType.RESOURCE;
            case ITEM -> ProductRewardCfg.RewardType.ITEM;
            case HERO, HERO_FRAGMENT, STAMINA, PRIVILEGE -> throw new IllegalArgumentException(
                    "product_reward.rewardType 目前只允许 RESOURCE / ITEM，收到 " + type
                            + "：武将、体力与特权各有自己的落库通路，不能由这张表混着发");
        };
    }

    // ---------- 当前生效的权益 ----------

    /**
     * 从存档的权益位 + 商品表推出<b>此刻</b>生效的付费权益（B19 §一.1a 的免广告与队列 +1）。
     *
     * <p><b>每次现推、不写进城建存档</b>：临时权益落库的那一刻起就等着和到期时刻打架。
     * 服务端不跑定时器（B00 陷阱 2），没有人会在到期那一秒去把 +1 收走 —— 而推导不会漏收。
     */
    public Entitlements entitlements(PlayerPaid paid, long now) {
        if (paid == null || !paid.cardActive(now)) {
            return new Entitlements(false, 0L);
        }
        boolean adFree = false;
        long bonusQueues = 0L;
        for (PayProductCfg row : configs.all(PayProductCfg.class)) {
            // 只认月卡这一族：adFree / extraQueues 两列对别的商品没有意义（表 designNote 第 4 条）
            if (row.kind() != PayProductCfg.Kind.MONTHLY_CARD) {
                continue;
            }
            adFree = adFree || row.adFree();
            bonusQueues += row.extraQueues();
        }
        return new Entitlements(adFree, bonusQueues);
    }

    /** 此刻生效的付费权益（城建读它，不读商品表也不读存档细节）。 */
    public record Entitlements(boolean adFree, long bonusQueues) {

        public int bonusQueuesAsInt() {
            return (int) Math.max(0L, Math.min(bonusQueues, Integer.MAX_VALUE));
        }
    }

    private static long levelOf(ProductRewardCfg row) {
        return row.requireMainLevel() == null ? 0L : row.requireMainLevel();
    }
}
