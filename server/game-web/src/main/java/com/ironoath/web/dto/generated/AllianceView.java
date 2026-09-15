// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 联盟视图。**version 是 diff 同步的核心**（B10 验收 10、禁止项：不要让联盟数据每帧全量同步）：客户端上报手里的 version，服务端只在版本更高时下发变化项。与 B07 地图 chunk 的版本号是同一套思路，只是粒度从「块」变成「联盟」。
 */
public record AllianceView(
        String id,   // 联盟 id
        String name,   // 联盟名
        String tag,   // 联盟标签（显示在成员昵称后的方括号里，也是地图实体的 allianceTag）
        String leaderId,   // 盟主玩家 id
        int level,   // 联盟等级。决定人数上限、领地上限与科技上限（alliance_config 表）
        long exp,   // 当前等级内已累计的联盟经验
        long memberCap,   // 人数上限（30→50→80→120→150）。服务端按 alliance_config 查好后下发
        int memberCount,   // 当前成员数
        long fund,   // 联盟资金（公共资产，用于扩容与科技研究）
        List<AllianceTechView> techs,   // 已研究的联盟科技。<b>空数组表示一项都没研究</b>，与「没有这个字段」是两件事 —— 客户端要靠它区分「进度为 0」和「服务端还没实现」。表本身（alliance_tech）是随包下发的配置，客户端能自己画出货架，但研究到哪一级只有服务端知道。
        int territoryCount,   // 已建造的堡垒/旗帜数
        long territoryCap,   // 领地上限。刻意不与人数同比例增长：人数决定「能打多大的仗」，领地决定「能占多少资源加成」，后者若随人数线性放开，大盟会把地图上的资源点全部圈走
        AllianceRole myRole,   // 我在本盟的职位
        long myContribution,   // 我的贡献值
        int myDonateToday,   // 我今天已捐献的<b>档数</b>（= donateTiersUsed 的长度，冗余下发是为了让「X/N」这类展示不必客户端自己数）
        List<Integer> donateTiersUsed,   // 我今天<b>已经捐过哪几档</b>（档位序号，升序，取值 0..2）。2026-09-13 裁决把口径定成「每档每日一次」（B10 §2「每日 3 档（免费 / 资源 / 金币）」）之后这个列表才有意义 —— 在此之前服务端只给一个计数，客户端不知道今天该把哪个按钮摆出来，只能全摆出来等玩家点了收报错（收口清单 §三 那条 B10 缺口的原文）。**下发而不是让客户端猜**：铁律 1，「今天还能捐什么」只有一个答案。
        int donateDailyCap,   // 今日可捐档数上限，来源 alliance_config 当前联盟等级那行的 donationDailyCap。下发是为了让客户端显示「2/3 档」而不是把 3 写死（客户端的 generated 只有类型没有值）。
        String announcement,   // 联盟公告
        long version,   // 联盟数据版本号。客户端下次同步时带上来
        long serverNow)   // 服务端时间戳
{
}
