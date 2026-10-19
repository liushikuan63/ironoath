/**
 * 职责：资源类型的中文名 —— **服务端下发**，客户端只保留一张运行时映射。
 *
 * <p><b>为什么不再在客户端放一份</b>：名字住在 `contract/config/resource.json` 的 `name` 列，
 * 而**客户端没有配置表加载器**（表不进包，客户端只有生成的类型、没有表数据）⇒ 客户端拿不到表。
 * 客户端自己写一份的后果是**改表只改一半**（服务端认了新名，客户端还显示旧名），
 * 而两边各自自洽 ⇒ 单测 / 类型检查 / 构建全绿，没有一条断言会红。
 * （这份副本此前由 `client/tests/ResourceNames.test.ts` 逐字对账着 —— 那是**承认它是副本**的写法，
 * 现在副本没了，对账的那条也换成了「服务端下发的名字逐字等于表里的 name 列」。）
 *
 * <p><b>名字什么时候到</b>：随 `/player/init` 一次下发（`PlayerInitResp.resourceNames`），
 * 由 {@link applyResourceNames} 收下。**一次给全部而不是每个金额对象各带一个 name** 是有意的：
 * 面板上显示资源名的地方有十几处，其中好几处手里**只有类型没有金额对象**
 * （抽卡消耗、社交政策消耗、地图实体、战报指标）—— 逐个对象带 name 要改五份契约，
 * 一次下发则让客户端自己建映射，十八处调用点一行不用改。
 *
 * <p><b>还没下发时怎么办</b>：退��枚举原文（`WOOD`）。那不是好文案，
 * 但**空行更坏**（资源条少一行比多一行英文更难查），而且它只在「服务端还没回 init」的那一小段窗口里出现。
 */

/** 资源类型 → 中文名。**由服务端下发填入**，不是客户端抄的表。 */
let resourceNames: Readonly<Record<string, string>> = {}

/**
 * 收下服务端下发的资源名（`/player/init` 的 `resourceNames`）。
 *
 * <p><b>整份替换而不是逐条叠加</b>：服务端是这份数据的唯一主人，它给什么就是什么；
 * 客户端留着上一次收到的残条才是「第二份真相」。
 *
 * <p><b>空清单等于清空</b>：这样「服务端没给」与「服务端说没有」是同一件事，
 * 不会留下上一次登录时的旧名字。
 */
export function applyResourceNames(entries: ReadonlyArray<{ type: string, name: string }>): void {
  const next: Record<string, string> = {}
  for (const entry of entries) {
    if (entry.name === '') continue
    next[entry.type] = entry.name
  }
  resourceNames = next
}

/** 当前这份映射（只读）。给「这份数据从哪来」的断言用。 */
export function currentResourceNames(): Readonly<Record<string, string>> {
  return resourceNames
}

/**
 * 资源类型 → 中文名。**认不出的取值退回原文**（不返回空串）：
 * 资源条少一行比多一行英文更难查。
 */
export function resourceName(type: string): string {
  return resourceNames[type] ?? type
}

/**
 * 建筑类型 → 中文名。取值与 `contract/config/building.json` 的 `type` 列一一对应
 * （CORE / RESOURCE / UTILITY / MILITARY / DEFENSE / SCIENCE）。
 *
 * <p>和上面的资源名是**同一条情形、同一个理由**：`BuildOptionView.type` 下发的就是枚举原文，
 * 而建造选择器把它连同门槛一起印给了玩家 —— 屏幕上出现「RESOURCE · 需要主城 1 级」。
 * 这与收口清单 #255 的 `main_city`、资源条的 `WOOD` 是同一类缺陷的第三次出现。
 *
 * <p>正经修法是服务端在 `BuildOptionView` 上补下发中文名（那要动 city 契约 + 重生成双端 DTO），
 * 在那之前这里是与资源名并列的**唯一真源，不许再抄第二份**。
 */
export const BUILDING_TYPE_NAMES: Readonly<Record<string, string>> = {
  CORE: '核心', RESOURCE: '资源', UTILITY: '民生',
  MILITARY: '军事', DEFENSE: '防御', SCIENCE: '科技',
}

export function buildingTypeName(type: string): string {
  return BUILDING_TYPE_NAMES[type] ?? type
}
