/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 装备槽位（B06 §2.5：4 槽位）。取值与 hero.schema.json 里同名的 `EquipSlot` **逐项一致** —— 生成物全落在同一个包与同一个 TS 命名空间，两份不同形的同名 def 会互相覆盖（`check-contract-defs` 就是钉这一条的）。这里必须自带一份是因为生成器只支持同文件 `$ref`。
 */
export type EquipSlot =
  | 'WEAPON'
  | 'ARMOR'
  | 'MOUNT'
  | 'ACCESSORY'

/**
 * 装备稀有度。取值与 `equip.json` 的 `rarity` 列 ENUM 声明逐项一致。它决定 `forgeMax`（N=10 / SR=15，§五②），不决定单级价格 —— 价格跟着属性总和走。
 */
export type EquipRarity =
  | 'N'
  | 'R'
  | 'SR'
  | 'SSR'

/**
 * 为什么这一件现在不能强化。`NONE` = 没拦着（此时 `canForge=true`），其余两种都是真会发生的：
 * `MAX_LEVEL` 已到 `forgeMax`（此时 `nextCostIron=0`，别拿 0 当免费），`IRON_LOW` 铁不够。
 * 与科技那族同一个形状的理由也相同：**判定只在服务端一处**，`canForge` 与 `blockReason` 是同一次计算的两种读法
 * （布尔给按钮、枚举给提示文案）。客户端不许自己拿 `forgeLevel` 与 `forgeMax` 比一遍 —— 两份判定的分叉不报错，
 * 症状是「按钮亮着却按失败」。
 * 不声明「实例不存在」：那是参数错误，直接回错误码，不会出现在列表视图里。
 */
export type EquipForgeBlockReason =
  | 'NONE'
  | 'MAX_LEVEL'
  | 'IRON_LOW'

/**
 * 一件装备（一个实例）的完整状态。注意这里没有「持有数量」这一位：**数量 = 本数组里同 `equipId` 的元素个数**，
 * 因为强化等级挂在件上之后，同 id 的两件不再等价，一个 count 说不清它们。
 */
export interface EquipInstanceView {
  /** 实例号，服务端铸造，玩家生命周期内唯一。强化请求原样回传它 —— 它是「哪一件」的答案，而 `equipId` 只是「哪一行」。 */
  uid: string
  /** `equip.json` 的行 id。给界面查名字与图标用，不参与任何判定。 */
  equipId: string
  /** 表里的中文名（改表立刻生效，客户端不硬编码装备名）。 */
  name: string
  /** 槽位，界面按它分组。 */
  slot: EquipSlot
  /** 稀有度。 */
  rarity: EquipRarity
  /** 当前强化等级（+N）。0 是**合法的初始值**：新拿到的一件就是 +0。与科技等级「不存 0 占位」那条相反， 因为这里是逐件的账，省掉 0 就等于每次读列表都要区分「没穿过」与「没强化过」。 */
  forgeLevel: number
  /** 这一件的强化上限，来自 `equip.json` 的 `forgeMax` 列（N=10 / SR=15）。下发它是为了让进度条有分母，而不是让客户端去查表。 */
  forgeMax: number
  /** **含强化**后的武力（定点万分比：12 点 = 120000）。算式 = 表里原值 ×(1 + 5% × forgeLevel)，与进入武将属性的那个数同一次计算产出 —— 界面显示的值和结算用的值必须是同一个数，否则玩家会说「我明明有 13 点武力」。 */
  mightFixed: number
  /** 含强化后的统率（定点万分比）。 */
  commandFixed: number
  /** 含强化后的智力（定点万分比）。 */
  wisdomFixed: number
  /** 下一级要多少铁。已满级时为 0（与科技/国家科技同一口径：0 不表示免费，`blockReason=MAX_LEVEL` 才是要显示的话）。 */
  nextCostIron: number
  /** 按钮亮不亮。 */
  canForge: boolean
  /** 不亮的时候要说的那句话。 */
  blockReason: EquipForgeBlockReason
  /** 穿在哪个武将身上；省略 = 在包里。**这一位是只读派生值**（从武将名档反查 uid 得到），不另存一份真相： 「谁穿着它」的权威只有一个，就是 `HeroInstance.equips` 里那个 uid。 */
  wornByHeroId: string | null
}

/**
 * GET /equip/instances 响应：这个玩家的全部装备实例。整份下发而不分页 —— 与科技树同一条理由：
 * 装备上限是「16 行 × 少量件数」这个量级，而服务端替客户端决定该看哪几件会造出第二个真相。
 * 包里的与穿着的都在这一个数组里（靠 `wornByHeroId` 区分），因为强化对两者都开放：
 * 「穿着的不能强化」这种规则从来没被写进任何文档，而它一旦被客户端猜出来就再也收不掉了。
 */
export interface EquipInstanceListView {
  instances: EquipInstanceView[]
  /** 服务端时钟（epoch 毫秒）。本机制没有任何到期时间，带上它只为与其他列表响应同形，省掉客户端一处特例。 */
  serverNow: number
}

/**
 * POST /equip/forge 请求体（§二 草样原样）。一次只强化一件一级：没有「一键 +5」，
 * 因为逐级递增的价格意味着批量要按五档分别计价，而那正是最容易算错、也最难向玩家解释的地方。
 */
export interface EquipForgeReq {
  /** 幂等键。与城建/训练/研究同一套机制：双击与客户端重试都会重复投递， 而没有去重的强化会一次扣两级铁 —— 这是这条线上唯一一处「重试比不重试更糟」的地方。 */
  requestId: string
  /** 要强化的**那一件**的实例号。传配置行 id 会失败（`PARAM_INVALID`），错误信息里写明「要 uid 不是行 id」： 这个混淆在实例化之后必然发生，报错要说清去哪拿 uid。 */
  equipUid: string
}

/**
 * POST /equip/forge 响应：改完之后这一件的状态 + 本次花了多少、涨了多少。
 */
export interface EquipForgeResp {
  /** 强化后的这一件（等级、三维、下一级价格全在里面）。回整个视图而不是回三个字段：客户端刷新卡片只有一条路径， 不会出现「列表里那件还是旧等级，弹层里那件已经是新等级」。 */
  instance: EquipInstanceView
  /** 本次实际扣掉的铁。必为正：纯消耗、必成（§五②），所以没有「失败了退款一半」这种状态。 */
  costIron: number
  /** 本次强化带来的战力增量（刷新后）。穿在身上才涨战力，在包里时增量为 0 —— 这一位就是那句规则的机器化版本： 不是 0 就说明它此刻确实作用于某个武将。 */
  powerDelta: number
  serverNow: number
}
