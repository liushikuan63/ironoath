/**
 * 职责：跨面板共用的**玩家可见文案**（纯函数，node 可测）。
 * 依赖：无（刻意不 import 'cc'）。
 *
 * <p>为什么单独一层：同一句"一屏画不下"的提示原先在 7 个视图里各写一遍，
 * 八处都带着「（长列表需要 ScrollView，属编辑器资产）」—— 那是把工程术语印在玩家脸上。
 * 收成一个函数后改一处即全改，量词（项 / 场 / 封 / 条 / 关 / 个目标）由调用方给；
 * `scripts/check-player-copy-jargon.sh` 再盯着 scene/ 不许重新出现这类词。
 */

/** 一屏画不下时的提示。`hidden <= 0` 返回空串，调用方直接画，不需要再自己判一次。 */
export function truncatedNotice(unit: string, hidden: number): string {
  if (hidden <= 0) {
    return ''
  }
  return `另有 ${hidden} ${unit}未显示`
}
