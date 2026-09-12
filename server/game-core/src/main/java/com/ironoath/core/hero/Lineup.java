package com.ironoath.core.hero;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 职责：一套编队预设（B06 §4：每队 3 名武将 = 主将 + 2 副将，可编 3 套）。
 * 依赖：无（纯数据）。
 *
 * <p>位置可以为空（{@code null}），因为「刚抽到一名武将、还没来得及编队」是常态，
 * 把空位建模成异常会让新手第一步就撞上错误提示。
 * 但主将为空时整队视为未编成 —— 主将技能全队生效（B06 §4），没有主将就没有队伍加成。
 */
public record Lineup(int presetIndex, String main, List<String> subs) {

    public Lineup {
        if (presetIndex < 0) {
            throw new IllegalArgumentException("presetIndex 不得为负：" + presetIndex);
        }
        if (main != null && main.isBlank()) {
            throw new IllegalArgumentException("主将 id 不得为空白串，空位请传 null");
        }
        List<String> copied = new ArrayList<>(subs == null ? List.of() : subs);
        for (String sub : copied) {
            if (sub != null && sub.isBlank()) {
                throw new IllegalArgumentException("副将 id 不得为空白串，空位请传 null");
            }
        }
        // 不能用 List.copyOf：它拒绝 null 元素，而副将位为空正是用 null 表示的
        // （「刚抽到一名武将、还没来得及编队」是常态，把空位建模成异常会让新手第一步就撞错误提示）
        subs = Collections.unmodifiableList(copied);
    }

    public static Lineup empty(int presetIndex) {
        return new Lineup(presetIndex, null, List.of());
    }

    /** 队伍里所有武将 id（含主将与副将），跳过空位。顺序：主将在前。 */
    public List<String> members() {
        List<String> out = new ArrayList<>(subs.size() + 1);
        if (main != null) {
            out.add(main);
        }
        for (String sub : subs) {
            if (sub != null) {
                out.add(sub);
            }
        }
        return out;
    }

    public boolean isFormed() {
        return main != null;
    }
}
