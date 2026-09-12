package com.ironoath.core.resource;

/**
 * 职责：资源 id 常量的唯一归属（与 {@code contract/config/resource.json} 的行 id 一一对应）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>为什么要有这个类</b>：这些 id 原先以字面量散在服务端二十来处（金币尤其密集：
 * 掉落、扣费、退款、任务奖励、体力购买各写一份）。散落本身不会立刻出错 —— 拼写一致时行为是对的 ——
 * 但它让「金币口径改了要改哪几处」这个问题只能靠全局搜索回答，而搜索关键词是 {@code GOLD}
 * 这种在日志、注释、字段名里到处都是的词。收进一处之后，改的是常量，编译器会替我找齐调用点。
 *
 * <p><b>为什么不直接引用配置</b>：id 是<b>标识</b>而不是<b>数值</b>，铁律 1 管的是后者。
 * 把它们做成运行期查表的值会让「这个方法是干什么的」从代码里消失，
 * 而编译期常量在写错时就直接报错。
 *
 * <p><b>与协议枚举的一致性</b>由 {@code ContractEnumParityTest} 断言：
 * {@code ResourceType} 声明了 {@code x-enum-source = resource}，生成器会强制它的取值
 * 与资源表的 id 集合完全一致。所以这里加一个常量而忘了改表/改协议，构建会红，
 * 而不是等到线上抛 {@code IllegalArgumentException}。
 */
public final class ResourceIds {

    /** 木材。 */
    public static final String WOOD = "WOOD";
    /** 石料。 */
    public static final String STONE = "STONE";
    /** 铁矿：造兵的瓶颈资源。 */
    public static final String IRON = "IRON";
    /** 粮食。 */
    public static final String GRAIN = "GRAIN";
    /** 金币。唯一不参与掠夺保护的资源（kind=CURRENCY，被抢等于直接拿走玩家花的钱）。 */
    public static final String GOLD = "GOLD";
    /** 体力。不在仓库里，因此既没有保护额度也不参与掠夺结算。 */
    public static final String STAMINA = "STAMINA";

    private ResourceIds() {
    }
}
