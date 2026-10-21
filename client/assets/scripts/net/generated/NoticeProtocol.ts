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
  /** 按 priority 倒序（数字大者在前）排好的生效公告。 */
  notices: NoticeItem[]
}

/**
 * 白名单表的整表内容（热更取内容的响应）。
 */
export interface ConfigTableResp {
  /** 表名。**只有白名单内的表名会被取到**（白名单在服务端一处，见 global.HOT_UPDATE_TABLES）。 */
  name: string
  /** 该表的版本号（内容变更即变）。客户端拿它判断「要不要重画」：版本没变就不动界面，这是「安全刷新点」的另一半。 */
  version: number
  /** **整表 JSON 字符串**（含 table/version/rows）。为什么是字符串而不是结构化对象：表会增删，把每张表的行结构写进契约等于每加一张表就改双端代码并重新生成；而客户端本来就持有生成的 TS 类型，解析后按那个类型断言即可。**解析失败必须当失败处理**，不许静默用空表继续跑 —— 静默吞掉解析失败，症状是「公告位空了」而不是「热更坏了」。 */
  json: string
}
