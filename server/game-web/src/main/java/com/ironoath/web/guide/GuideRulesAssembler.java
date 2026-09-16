package com.ironoath.web.guide;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GuideCfg;
import com.ironoath.core.guide.GuideScript;
import com.ironoath.web.dto.generated.GuideStepView;
import com.ironoath.web.dto.generated.GuideTrigger;

/**
 * 职责：把 {@code guide.json} 装配成两份视图 —— 推进用的 {@link GuideScript}（core 规则对象）
 * 与下发用的 {@code List<GuideStepView>}（协议 DTO）。
 * 依赖：{@link ConfigRegistry}（配置表只读）。
 *
 * <p><b>每次调用都重建，不缓存</b>：脚本七行、每行十余个字段，重建的成本可以忽略；而热更
 * （{@code POST /ops/config/reload} 整体替换注册表）之后必须立刻生效，缓存一份就等于把「改脚本不改包」
 * 这条硬要求（验收 3）改成「等缓存过期」。这一族此前唯一的先例是 Bot 调度器 —— 它也是
 * 「规则一变就重建」，理由相同。
 *
 * <p><b>两份视图不是两份真相</b>：它们都从同一批 {@code GuideCfg} 行现算，字段各取所需
 * （core 那份只要参与规则的五位，DTO 那份只给展示字段且<b>不含 judge/judgeTarget</b> ——
 * 判据下发出去，将来就会有人在客户端"顺手"判完成）。
 */
@Component
public class GuideRulesAssembler {

    private final ConfigRegistry configs;

    public GuideRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
    }

    /**
     * 推进规则用的脚本。
     *
     * <p>序号取表里的 {@code stepIndex} 而<b>不取行序</b>：表的行序由 JSON 数组决定，改一次排版
     * 就换一次顺序，而 {@code GuideScript.of} 要求序号从 1 连续 —— 把行序当序号会让"调行序"变成
     * "改玩法"。填错的序号（跳号、重复）在这里直接抛，那是配置错误，不是运行时状态。
     */
    public GuideScript script() {
        List<GuideScript.Step> steps = new ArrayList<>();
        for (GuideCfg row : configs.all(GuideCfg.class)) {
            steps.add(new GuideScript.Step(row.id(), (int) row.stepIndex(), row.skippable(),
                    judgeOf(row.judge()), row.judgeTarget()));
        }
        return GuideScript.of(steps);
    }

    /**
     * 下发用的步骤视图，顺序即表序。
     *
     * <p>服务端不重排：客户端也按 {@code stepIndex} 显示「第 3/7 步」，两处排序迟早分叉。
     */
    public List<GuideStepView> views() {
        List<GuideStepView> views = new ArrayList<>();
        for (GuideCfg row : configs.all(GuideCfg.class)) {
            views.add(new GuideStepView(row.id(), row.name(), row.stepIndex(),
                    GuideTrigger.valueOf(row.trigger().name()),
                    row.panelKey(), row.highlightPath(), row.maskArea(), row.text(), row.skippable()));
        }
        return views;
    }

    /**
     * 脚本版本 = 表 {@code version}。客户端拿它决定要不要重画（缓存的是展示，权威仍是服务端）。
     *
     * <p>只给号数不给内容哈希：哈希会变而号数不会，改一步而忘记 bump 版本是配置纪律问题，
     * 与 {@code ReleaseRulesAssembler} 那份配置清单同一口径（那里连内容哈希一起给，是因为它服务于回滚）。
     */
    public String version() {
        return String.valueOf(configs.rawTable("guide").version());
    }

    /** 配置表枚举 → core 枚举。写成穷尽 switch，加一个取值就要在这里表态。 */
    private static GuideScript.Judge judgeOf(GuideCfg.Judge judge) {
        return switch (judge) {
            case QUEST_DONE -> GuideScript.Judge.QUEST_DONE;
            case QUEST_CLAIMED -> GuideScript.Judge.QUEST_CLAIMED;
        };
    }
}
