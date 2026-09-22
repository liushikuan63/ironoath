package com.ironoath.core.player;

/**
 * 职责：别人家的存档在<b>列表行</b>上需要露出的那几项（六边形架构的读模型，纯数据）。
 * 依赖：无。
 *
 * <p><b>为什么不直接给 {@link PlayerSave}</b>：一份存档里装着资源表、PVP 账本、荣耀、引导、
 * 付费权益、科技、礼包弹窗账本、已拥有的头像框集合，而联盟成员行只用得上昵称 + 展示战力 +
 * 最近活跃，申请页与城行只用昵称 + 主城等级。整档读回来的那些字段一个都不看，
 * 却要为此付出反序列化的全部代价 —— 那一趟的字节数随玩法批次只增不减，
 * 而列表行的字段数不会跟着长。
 *
 * <p>这条界线必须由类型守住，不能靠自觉：只要返回值还是 {@code PlayerSave}，
 * 下一个调用方就会顺手多读一项，投影口在两次"顺手"之后又变回整档读。
 * 这里没有暴露任何集合与子对象，多要一项只能显式地回到端口上加字段 —— 那会被两份实现与等价测试看见。
 *
 * @param playerId     玩家 id（与 {@code findByPlayerIds} 的返回键同一条口径）
 * @param nickName     昵称，服务端下发；客户端没有玩家表，拿 id 猜出来的名字就是第二真源
 * @param cityLevel    主城等级；读不到就是 0，与改之前那两个助手同口径
 * @param lastLoginAt  最近登录时刻（服务端时钟），关注列表用它画"最近活跃"
 * @param displayPower 展示战力，只用于 UI 与榜单，<b>不得</b>拿去做 PVP 匹配（铁律 11）；
 *                     存档里没有战力三元组时为 0
 */
public record PlayerBrief(
        String playerId,
        String nickName,
        int cityLevel,
        long lastLoginAt,
        long displayPower) {
}
