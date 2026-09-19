/**
 * 职责：面板上「这一步还点不了」那几句玩家文案（客户端唯一一份）。
 * 依赖：无（纯字符串拼装，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>为什么要收进来</b>：这五句原先各写各的，四句带「…选择器未接入」这类工程话，
 * 两句还把 `stageId`/`reportId` 直接印进提示 —— 与 #255 建筑名 / #268 资源名 / #278 技能名 /
 * #281 碎片名 / #288 赛季行 id 是同一条外泄：玩家读到的是一串内部编号，而不是一句人话。
 * 收在一处之后，判据只有一条且能失败：**任何一句都不许出现内部 id 与工程黑话**
 * （见 `client/tests/BlockedPickCopy.test.ts`，另有 `scripts/check-player-copy-jargon.js` 兜词表）。
 */

/**
 * 「要先选某样东西，但这一屏给不出这个选择」。
 *
 * <p>`what` 必须是玩家看得见的说法（「出战阵容」「发到哪个频道」），
 * 不能传内部编号 —— 拼出来的整句会直接进提示。
 */
export function pickUnavailable(what: string): string {
  return `这一步要先选${what}，这里还选不了`
}

/**
 * 「某件事可以做，但这一屏点不出那个动作」（举报、拉黑这类行内动作）。
 *
 * <p>与 {@link pickUnavailable} 分两条是因为语义不同：那条缺的是"选一个东西"，
 * 这条缺的是"做出一个动作"，合成一个函数会写出「这一步要先选举报」这种不通的话。
 */
export function actionUnavailable(what: string): string {
  return `这里还点不出${what}，请稍后再试`
}
