package com.ironoath.web.quest;

/**
 * 任务列表要哪一族。
 *
 * <p><b>为什么分族</b>：{@code /quest/list} 一次全发 76 行 ⇒ 响应体 28513B ⇒
 * <b>超了全局响应预算 {@code PERF_PAYLOAD_MAX_BYTES}（20480B）</b>
 * （探针 {@code verify-perf-runtime} 实测 28162B，超 37%）。而客户端的任务面板本来就把
 * 任务分成三个页签（任务 / 成就 / 首杀图鉴），<b>各族单独发都在预算内</b>
 * （实测：任务 17 行 6273B、成就 9 行 3453B、首杀 50 行 18877B）
 * ⇒ ⇒ <b>服务端按族下发，客户端体验不变</b>（面板本来就在页签内翻页，族内行数不变）。
 *
 * <p><b>口径必须与客户端的本地过滤逐一对应</b>：客户端原来按
 * {@code goalType == 'SOLO_MONSTER_WIN' ? 'FIRST_KILL' : questType} 分族
 * ⇒ 首杀那 50 行的 questType 也是 ACHIEVEMENT ⇒ <b>FIRST_KILL 优先于 ACHIEVEMENT</b>
 * （顺序反了就会把首杀行发进成就族）。口径在 {@code QuestAppService#scopeOf}。
 *
 * <p><b>它不是协议里的字段</b>：请求用查询参数带它，<b>响应不重复它</b> ——
 * 客户端知道自己请求的是哪一族（{@code GET /quest/list?scope=…}），
 * 响应里再带一遍就是同一件事说了两次。
 */
public enum QuestScope {
    /** 任务页：不是成就、也不是首杀的那些。 */
    QUEST,
    /** 成就页：questType=ACHIEVEMENT 且不是首杀的那些。 */
    ACHIEVEMENT,
    /** 首杀图鉴页：goalType=SOLO_MONSTER_WIN 的那些。 */
    FIRST_KILL;

    /** 从请求参数解析；不认识的族名退到「任务」族（不抛错，见控制器的说明）。 */
    public static QuestScope parse(String raw) {
        for (QuestScope scope : values()) {
            if (scope.name().equals(raw)) {
                return scope;
            }
        }
        return QUEST;
    }
}
