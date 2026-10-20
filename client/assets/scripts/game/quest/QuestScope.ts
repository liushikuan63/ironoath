/**
 * 任务列表要哪一族（请求用查询参数带它）。
 *
 * <p><b>为什么客户端也有一份</b>：它与服务端 `com.ironoath.web.quest.QuestScope`
 * 是同一份口径的**两份实现**（跨端没有共享代码），所以两边都写清判据：
 * 面板的三个页签（任务 / 成就 / 首杀图鉴）各自对应一族，
 * 判据是 `goalType=SOLO_MONSTER_WIN` ⇒ 首杀优先，其次 `questType=ACHIEVEMENT`。
 *
 * <p><b>为什么响应里不重复它</b>：客户端知道自己请求的是哪一族
 * （`GET /quest/list?scope=…`），响应里再带一遍就是同一件事说了两次。
 */
export type QuestScope = 'QUEST' | 'ACHIEVEMENT' | 'FIRST_KILL'
