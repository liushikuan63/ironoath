/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 一条当前生效的运营公告。文案由服务端原样下发，客户端只展示。
 */
export interface NoticeItem {
  /** 公告 id。**定位用，不印给玩家** —— 客户端拿它去拉表内容与做去重，界面上出现的是 title 与 body。 */
  id: string
  /** 公告标题，服务端原样下发。 */
  title: string
  /** 公告正文，服务端原样下发。**不允许客户端拼装或截断**：改文案是运营的日常动作，而客户端硬编码的文案在提审期被要求修改时只能发版。 */
  body: string
  /** 排序权重，**数字大者排前**。由服务端排序，客户端不重排（重排就会出现第二个排序真源）。 */
  priority: number
}

/**
 * 当前生效的公告列表。空列表是合法结果（没有公告时客户端不画面板，而不是画一个空的）。
 */
export interface NoticeListResp {
  /** 当前可见公告列表的内容 hash（SHA-256 前 16 位十六进制），不是人工表版本。文案变更、排序变更或服务端时刻跨过生效窗口都会变化；内容相同则无需重绘。 */
  version: string
  /** 按 priority 倒序（数字大者在前）排好的生效公告。 */
  notices: NoticeItem[]
}

/**
 * 白名单表的整表内容（热更取内容的响应）。
 */
export interface ConfigTableResp {
  /** 表名。**只有白名单内的表名会被取到**（白名单在服务端一处，见 global.HOT_UPDATE_TABLES）。 */
  name: string
  /** 人工维护的表版本，只供诊断。内容可在不升版本时变化，客户端是否刷新必须按 hash 判。 */
  version: number
  /** 表原始行内容 SHA-256 的前 16 位十六进制，与客户端清单同源；取表时从同一份不可变快照计算。 */
  hash: string
  /** 仅含白名单展示字段的 JSON 字符串（table/version/rows）；不会把内部 why 等文档字段下发。首版只有 notice，扩表需同步审查投影。解析失败必须显式报错，不能静默当空表。 */
  json: string
}
