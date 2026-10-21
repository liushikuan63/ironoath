package com.ironoath.web.notice;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.NoticeCfg;
import com.ironoath.web.dto.generated.NoticeItem;
import com.ironoath.web.dto.generated.NoticeListResp;

/**
 * 职责：运营公告的读侧（收口清单 #2287 ⑤ / V26-1）。
 * 依赖：game-config（notice 表）、game-common（时间）。
 *
 * <p><b>为什么公告是「客户端热更白名单」的第一张表</b>：热更要能改东西，而**能改的必须是
 * 不影响玩家判定的展示内容** —— 战斗与经济数值改了却不同步发版，玩家的账就与别人的不一样。
 * 公告只有标题、正文、生效窗口与排序权重，**表里没有任何数值**（数值一律服务端算好再下发），
 * 所以改它不会动到任何人的结算。
 *
 * <p><b>生效窗口与排序都由服务端算</b>：客户端的时钟可以被玩家改，所以「这条公告该不该显示」
 * 若由客户端判，症状就是「该显示时没显示 / 过期了还在显示」，而两边都觉得自己没错。
 * 同理排序也在服务端做完（客户端不重排 —— 重排就会出现第二个排序真源）。
 *
 * <p><b>why 不下发</b>：{@code NoticeCfg} 带 why（生成物把表结构如实搬过来），但 why 是给
 * 下一个改表的人看的内部说明，**不印给玩家** ⇒ 响应里只放 id/title/body/priority 四项。
 */
@Service
public class NoticeService {

    private final ConfigRegistry configs;
    private final TimeService timeService;

    public NoticeService(ConfigRegistry configs, TimeService timeService) {
        this.configs = configs;
        this.timeService = timeService;
    }

    /**
     * 当前生效的公告（按 priority 倒序：数字大者在前）。
     *
     * <p>空列表是合法结果 —— 没有公告时客户端不画面板，而不是画一个空的公告位。
     *
     * <p><b>响应带 notice 表的版本号</b>：客户端拿它判断「要不要重画」——
     * 拉一次是真的，但版本没变就别让玩家看见界面闪一下。
     */
    public NoticeListResp list() {
        long now = timeService.serverNow();
        List<NoticeItem> live = configs.all(NoticeCfg.class).stream()
                .filter(row -> inWindow(row, now))
                .sorted(Comparator.comparingLong(NoticeCfg::priority).reversed())
                .map(row -> new NoticeItem(row.id(), row.title(), row.body(), row.priority()))
                .toList();
        return new NoticeListResp(tableVersion(), live);
    }

    /**
     * notice 表的版本号（内容变更即变）。
     *
     * <p>为什么用**表版本**而不是「生效公告条数」：条数不变而文案改了（最常见的运营动作）时，
     * 条数版会让客户端以为没变而继续显示旧文案 ⇒ 症状是「改了公告没反应」。
     */
    private int tableVersion() {
        return configs.rawTable("notice").version();
    }

    /**
     * 生效窗口：{@code startAt}/{@code endAt} 为 0 表示该侧不限。
     *
     * <p>为什么 0 表示不限而不是「1970 年」：表里绝大多数公告是长期有效的，用一个具体时间戳
     * 去表达「没有边界」既啰嗦又容易写错 ⇒ 约定 0 = 不限，另一侧照常判。
     */
    private static boolean inWindow(NoticeCfg row, long now) {
        if (row.startAt() > 0L && now < row.startAt()) {
            return false;
        }
        return row.endAt() == 0L || now <= row.endAt();
    }
}
