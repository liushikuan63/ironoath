/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 一步在什么时候弹出来。**只有真被表用到的两个取值**：不预声明「首次进入战斗」这类没人用的触发器（B18 禁止项）。
 */
export type GuideTrigger =
  | 'PANEL_OPEN'
  | 'STATE_REACHED'

/**
 * 玩家对一步做了什么。`COMPLETE` 是「我做完了这一步」（客户端只有上报权，能不能推进由服务端按状态判），`SKIP` 是「跳过」（仅 `skippable=true` 的步骤允许）。
 */
export type GuideAction =
  | 'COMPLETE'
  | 'SKIP'

/**
 * 一步的展示参数。全部字段都来自 `guide.json` 的同一行 —— 客户端不拼文案、不填坐标、不猜顺序。
 */
export interface GuideStepView {
  /** 步骤 id。上报进度时原样回传（服务端按它查当前步，不认下标）。 */
  id: string
  /** 步骤名（给埋点与客服看的短名；玩家看到的是 text）。 */
  name: string
  /** 序号。**顺序的唯一来源是表**，下发序号只为让客户端能显示「第 3/7 步」这种进度而不必自己数。 */
  stepIndex: number
  /** 触发时机。 */
  trigger: GuideTrigger
  /** 要打开/已在的面板 key（`PanelNav` 的那套：city / quest / army / targets / hero / social）。可空：`STATE_REACHED` 那一步不需要具体面板。 */
  panelKey: string | null
  /** 高亮定位（按面板 key 体系的节点路径）。可空表示只提示不指东西 —— 挖洞遮罩没有高亮目标时就是整屏暗。 */
  highlightPath: string | null
  /** 遮罩范围：`full` 或 `x,y,w,h`。本批 7 步全是 `full`（B18 验收 6 要的是「遮罩吃触摸」，与几何无关），而格式留着是因为机制上确实需要矩形那一种。 */
  maskArea: string
  /** 气泡文案。**只在表里存一份**：客户端硬编码一句就会让「改文案要发包」变成事实（B12 禁止项）。 */
  text: string
  /** 能不能跳。`false` 时界面不显示跳过按钮，硬发 SKIP 会被拒（`GUIDE_STEP_NOT_SKIPPABLE`）。 */
  skippable: boolean
}

/**
 * 一次脚本下发。体积受 `PERF_PAYLOAD_MAX_BYTES` 约束（验收 7）：7 步 × 一句文案远小于预算，真超了要按章节分页而不是悄悄超。
 */
export interface GuideScriptResp {
  /** 全部步骤，顺序即表序（服务端不重排，客户端不许自己排 —— 两处排序迟早分叉）。 */
  steps: GuideStepView[]
  /** 脚本版本 = `guide.json` 的 version。服务端**不做条件返回**（七步的响应实测不到 1 KB，省流量是零收益，而一个不改变行为的 ?version= 参数会成为接口上的假象）：客户端自己拿它与手上那份比对，相同就不必重画；热更之后版本号会变，这就是验收 3 的另一半。 */
  version: string
  /** 该玩家当前该做哪一步（**续传的那一位**：进度落服务端存档，杀进程重进回到这里而不是从头）。null 有两种：已经走完，或这个号压根不该看引导（applies=false 且从没开始过）；从没开始但该看时下发 1 —— 与 `finished` 配合，不重复发奖也不重复打扰。 */
  nextStepIndex: number | null
  /** 这个账号该不该走引导（B18 §五③：只对新号）。false 时 `steps` 仍会给出（客户端不必分两套代码），但界面不显示引导。 */
  applies: boolean
  /** 服务端时刻（毫秒）。引导里任何「等待/冷却」的展示都由它算，不用本地时钟（铁律 5）。 */
  serverNow: number
}

/**
 * 上报一步的结果。
 */
export interface GuideProgressReq {
  /** 玩家针对哪一步上报。服务端会校验它**是不是当前那一步**（`GUIDE_STEP_OUT_OF_ORDER`）：不校验顺序就等于允许重放刷进度。 */
  stepId: string
  /** 做完还是跳过。 */
  action: GuideAction
  /** 幂等键，与任务/邮件/活动领奖同一条要求（弱网重投不该把两步并作一步）。 */
  requestId: string
}

/**
 * 上报结果。三个字段各说各的：有没有推进、是否走完、下一步是哪一步。
 */
export interface GuideProgressResp {
  /** 本次上报是否真的推进了进度。**状态没达成时为 false**（而不是报错）—— 玩家点了「我做完了」但主城还没升到 2 级是正常情况：引导要留在这一步等他。 */
  advanced: boolean
  /** 全部步骤已走完（或被跳完）。客户端据此收起引导层，不再弹下一步。 */
  finished: boolean
  /** 推进之后该做哪一步；`finished` 为 true 时为 null。 */
  nextStepIndex: number | null
}
