package com.ironoath.web.store.mongo;

import com.ironoath.core.player.PlayerRepository;
import org.springframework.data.annotation.Id;

/**
 * 职责：{@code player} 集合的<b>列表行读模型</b> —— 只声明 {@link PlayerRepository#findBriefs}
 * 要投影出来的那几列（持久化层专用，不进领域模型）。
 * 依赖：{@link PlayerDocument} 的集合名与 {@link PlayerDocument.PowerDoc} 的嵌套形态。
 *
 * <p><b>为什么不直接把字段投影查询打在 {@link PlayerDocument} 上</b>：那是一个 19 个分量的
 * record，其中 {@code avatarId}、{@code createdAt}、{@code version} 是<b>原始类型</b>。
 * 投影查询不会返回它们，而 Spring Data 给 record 的构造器传 null 时当场抛
 * {@code Parameter avatarId must not be null} —— 这条读法会在第一次真实调用时炸掉，
 * 且只在 Mongo 版炸（内存版没有文档模型）。这个读模型只声明被投影的列，
 * 所以「列没投出来」与「多读了没有声明的列」都不会发生。
 *
 * <p>它同时是这条读法的<b>类型边界</b>：想多拿一项就得先改这个 record 与投影列表，
 * 而这两处都在 {@code PlayerBriefProjectionQueryTest} 的判据里。
 *
 * @param playerId    主键，即玩家 id
 * @param nickName    昵称
 * @param cityLevel   主城等级
 * @param lastLoginAt 最近登录时刻
 * @param power       战力子文档；<b>可空</b>（B08 之前建的号没有这一位），读成 0 战力而不是抛
 */
public record PlayerBriefDocument(
        @Id String playerId,
        String nickName,
        int cityLevel,
        long lastLoginAt,
        PlayerDocument.PowerDoc power) {
}
