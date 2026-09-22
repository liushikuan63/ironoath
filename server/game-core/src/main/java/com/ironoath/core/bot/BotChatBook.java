package com.ironoath.core.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.ironoath.common.rng.Rng;

/**
 * 职责：Bot 的聊天句库（B11 §四「事件触发模板句库」）—— 按场景与等级门槛加权挑一句。
 * 依赖：game-common 的 Rng（纯 Java，零框架）。
 *
 * <p><b>为什么句库要一个 core 类型，而不是在装配器里直接挑</b>：挑话这件事有两条不变量
 * （等级门槛必须真的参与筛选、权重必须成正比例），而它们是"像人"的一部分 ——
 * 把这两条写进 web 侧的 lambda 里，下一个人就会在另一处再写一遍。
 *
 * <p><b>没有占位符替换</b>：{@code bot_chat} 表里 18 行的正文都是完整句子，没有任何 <code>{}</code>
 * 占位符。B11 §四 提到"占位符替换"，但表里没给模板参数 —— 在这里发明一套参数名就是发明规格。
 * 等表里真的出现占位符再加，届时本类加一个 {@code Map<String,String>} 参数即可。
 *
 * <p><b>场景与行为树的对应</b>：{@link Scene} 与 {@code bot_chat.scene} 的枚举逐字一致
 * （**这一条目前没有判据钉着**：查过 BotConfigTest 的 everyChatSceneHasAtLeastOneLine、BotEventChatTest、ContractEnumParityTest，三条都不比对 {@link Scene} 与 {@code bot_chat.scene} 里的 ENUM 值）。哪些场景今天有生产者、哪些还没有，
 * 写在 {@code BotWorldAdapter} 的聊天段注释里 —— 表里 18 行并不都可达，这一点要说清楚而不是假装都接了。
 */
public final class BotChatBook {

    /** 聊天场景。取值与 {@code bot_chat.scene} 的 ENUM 声明逐字一致。 */
    public enum Scene {
        /** 求助（升级/训练/治疗被卡住时的抱怨与求援）。 */
        HELP_REQUEST,
        /** 号召集结。 */
        RALLY_CALL,
        /** 被攻击。 */
        ATTACKED,
        /** 打赢了。 */
        VICTORY,
        /** 打输了。 */
        DEFEAT,
        /** 无事可做的闲聊。 */
        CHAT_IDLE,
        /** 刚加入联盟的问候。 */
        ALLIANCE_JOIN,
        /** 买卖资源的口风。 */
        TRADE;

        /**
         * 这个场景是不是「被某件事触发」的（B11 §四「事件触发模板句库」）。
         *
         * <p><b>为什么要有这个分类</b>：触发型场景的说话时机由事件决定（被打的瞬间、发起集结的瞬间），
         * 而闲聊型由 tick 的自发节奏决定。事件方只能发触发型 —— 让「战斗结算」去挑一句闲聊，
         * 或者让「定时聊天」去喊一句「来人集结」，都是立刻能被认出来的假。
         */
        public boolean eventDriven() {
            return this == HELP_REQUEST || this == RALLY_CALL || this == ATTACKED
                    || this == VICTORY || this == DEFEAT;
        }

        /**
         * 战斗结果的场景化：赢了说 {@link #VICTORY}、输了说 {@link #DEFEAT}。
         *
         * <p>映射只有这一处 —— 让调用方自己写 {@code won ? VICTORY : DEFEAT} 也是同样的意思，
         * 但它会在第二处再被写一遍，而两处哪天分支反了（把败说成胜）不会有任何编译期信号。
         */
        public static Scene ofBattle(boolean won) {
            return won ? VICTORY : DEFEAT;
        }
    }

    /**
     * 一句话。
     *
     * @param id           行 id（日志里用它定位"刚才发的是哪一句"）
     * @param scene        场景
     * @param text         正文。直接发给聊天频道，不含占位符
     * @param weight       同场景内的相对权重（正整数；比例才有意义，绝对值没有）
     * @param minCityLevel 主城等级下界（含）
     * @param maxCityLevel 主城等级上界（含）；null 表示不设上限
     */
    public record Line(String id, Scene scene, String text, int weight,
                       long minCityLevel, Long maxCityLevel) {
        public Line {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("句库行 id 不得为空");
            }
            if (scene == null) {
                throw new IllegalArgumentException("句库行 " + id + " 的 scene 不得为空");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("句库行 " + id + " 的正文不得为空");
            }
            if (weight < 1) {
                throw new IllegalArgumentException("句库行 " + id + " 的权重必须 >= 1，实际=" + weight
                        + "：权重为 0 的行永远不会被抽到，应当从表里删掉而不是留着");
            }
            if (minCityLevel < 0L) {
                throw new IllegalArgumentException("句库行 " + id + " 的等级下界不得为负");
            }
            if (maxCityLevel != null && maxCityLevel < minCityLevel) {
                throw new IllegalArgumentException("句库行 " + id + " 的等级区间非法：[" + minCityLevel
                        + ", " + maxCityLevel + "]");
            }
        }

        /** 这一句在主城 {@code cityLevel} 级时是否可说。 */
        public boolean allows(int cityLevel) {
            return cityLevel >= minCityLevel && (maxCityLevel == null || cityLevel <= maxCityLevel);
        }
    }

    /** 全部句库行。构造期只做"非空"这一条结构校验，逐行的约束在 {@link Line} 里。 */
    public record Rules(List<Line> lines) {
        public Rules {
            if (lines == null || lines.isEmpty()) {
                throw new IllegalArgumentException("BOT 句库不得为空：没有句子的聊天等于没有聊天");
            }
            lines = List.copyOf(lines);
        }

        /** 某个场景在当前等级下可用的行（可能为空：等级没到门槛）。 */
        public List<Line> candidates(Scene scene, int cityLevel) {
            List<Line> out = new ArrayList<>();
            for (Line line : lines) {
                if (line.scene() == scene && line.allows(cityLevel)) {
                    out.add(line);
                }
            }
            return Collections.unmodifiableList(out);
        }
    }

    private final Rules rules;

    public BotChatBook(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    /**
     * 在某个场景里按权重挑一句。
     *
     * <p><b>按权重抽而不是取第一句</b>：同场景多句的权重就是"这句话出现的相对频率"，
     * 取第一句会让权重列形同虚设（而权重列是运营调"哪类话更常见"的唯一旋钮）。
     *
     * @return 抽到的句子；该场景在当前等级下一句都不匹配时返回空 —— <b>空是正常结果</b>
     *         （等级没到门槛），不是错误，调用方安静跳过即可
     */
    public Optional<Line> pick(Rng rng, Scene scene, int cityLevel) {
        if (rng == null || scene == null) {
            throw new IllegalArgumentException("rng 与 scene 都不得为 null");
        }
        List<Line> pool = rules.candidates(scene, cityLevel);
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        int total = 0;
        for (Line line : pool) {
            total += line.weight();
        }
        int roll = (int) rng.range(0, total - 1);
        int accumulator = 0;
        for (Line line : pool) {
            accumulator += line.weight();
            if (roll < accumulator) {
                return Optional.of(line);
            }
        }
        // 走到这里只可能是权重表被并发改动；退回最后一句而不是抛错（同 BotSchedule.pickHour 的理由）
        return Optional.of(pool.get(pool.size() - 1));
    }

    /** 所有场景（供装配器与诊断检查"表里的场景是否都被这个枚举认得"）。 */
    public List<Scene> scenes() {
        List<Scene> out = new ArrayList<>();
        for (Line line : rules.lines()) {
            if (!out.contains(line.scene())) {
                out.add(line.scene());
            }
        }
        return Collections.unmodifiableList(out);
    }

    public Rules rules() {
        return rules;
    }
}
