/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 武将稀有度。取值与 hero 表、hero_rarity 表、item 表的 rarity 列一致，声明顺序为升序（N→SSR）。
 */
export type HeroRarity =
  | 'N'
  | 'R'
  | 'SR'
  | 'SSR'

/**
 * 武将三维属性名。与 hero 表的 might/command/wisdom 三列对应。
 */
export type HeroAttr =
  | 'MIGHT'
  | 'COMMAND'
  | 'WISDOM'

/**
 * 技能位（B06 §2.4）。主技能全队生效，副技能只在副将位生效。
 */
export type SkillSlot =
  | 'MAIN'
  | 'SUB'

/**
 * 装备槽位（B06 §2.5：4 槽位）。取值与 equip 表的 slot 列一致。
 */
export type EquipSlot =
  | 'WEAPON'
  | 'ARMOR'
  | 'MOUNT'
  | 'ACCESSORY'

/**
 * 加成落在哪个乘区（B06 硬约束 1：配置表里要明确标注每个加成落在哪个乘区）。HERO=乘区A武将本体、BOND=乘区A的缘分子乘区、EQUIP_SET=装备乘区（B05 §1.3 的六乘区之一，与武将乘区隔离）。
 */
export type BonusZone =
  | 'HERO'
  | 'BOND'
  | 'EQUIP_SET'

/**
 * 三维属性。base 是配置表原值，final 是养成后的值（等级/星级/觉醒/装备共同作用）。
 */
export interface AttrTriple {
  might: number
  command: number
  wisdom: number
}

/**
 * 加成明细的一行。zone 必填 —— B06 硬约束 1 要求每个加成都标明落在哪个乘区，玩家侧的「为什么我这么强」与开发侧的「数值为什么算错」都靠它。
 */
export interface BonusBreak {
  /** 来源标签，直接展示，如「裴惊澜 Lv60 ★4（主将）」 */
  source: string
  /** 定点加成值（×10000），如 6200 = +62% */
  value: number
  zone: BonusZone
}

/**
 * 队伍的武将侧加成，按乘区拆分返回（B06 §2）。<b>与 B06 文档草案的差异（有意偏离，理由如下）</b>：草案写的是 atkMultiplier/defMultiplier/hpMultiplier，但 B05 已交付的战斗内核只有两个武将侧乘区（HeroSnapshot.heroBonusFixed 攻击、defBonusFixed 防御），没有独立的生命乘区 —— B05 把生命折进了有效防御（HP_DEFENSE_WEIGHT）。所以这里返回 atkFixed/defFixed/skillFixed：武力→攻击乘区、统率→防御乘区、智力→技能强度。硬造一个内核消费不了的 hpMultiplier 只会让面板显示一个不影响战斗的数字。
 */
export interface HeroBonus {
  /** 攻击乘区加成（定点）。进 BattleSnapshot 的 heroBonusFixed */
  atkFixed: number
  /** 防御乘区加成（定点）。进 defBonusFixed */
  defFixed: number
  /** 技能强度加成（定点），由智力折算 */
  skillFixed: number
  /** 队伍统帅值合计（主将 100% + 副将各 50%），决定带兵上限 */
  commandValue: number
  /** 是否触到了 HERO_ZONE_CAP 上限。为 true 说明继续堆养成不会再变强，UI 应提示玩家 */
  capped: boolean
  breakdown: BonusBreak[]
}

/**
 * 一名已拥有武将的完整状态（五条养成线各占一段）。
 */
export interface HeroView {
  heroId: string
  /** 中文名，来自 hero 表 */
  name: string
  rarity: HeroRarity
  level: number
  /** 当前等级内已累计的经验（溢出的经验会连续升级，见 HeroGrowth） */
  exp: number
  /** 升到下一级还需多少经验；满级时为 0 */
  expToNext: number
  maxLevel: number
  star: number
  maxStar: number
  awaken: number
  /** 来自 hero 表 awakenMax，逐武将不同（SSR 3 / SR 2 / R 1） */
  maxAwaken: number
  mainSkillId: string
  /** 主技能的中文名（skill 表的 name 列，服务端按 mainSkillId 查表随视图下发）。 **为什么名字要服务端给**：武将页那一行原先直接印 `skill_guanyu_main Lv3/10` —— 把配置行 id 印给玩家。 这与 #255（建筑显示名）、#268（资源中文名）同一族，裁决早就定了：客户端不许自己拼名字、也不许抄一份第二真源。 */
  mainSkillName: string
  mainSkillLevel: number
  subSkillId: string
  /** 副技能的中文名，取法与 mainSkillName 完全一致（同一行代码写出来的两个字段，不该有一个是 id）。 */
  subSkillName: string
  subSkillLevel: number
  maxSkillLevel: number
  /** 这个武将身上穿着的装备，**每项自带槽位**（见 WornEquip）。 **为什么不再是定长 4 格**：原先是 `(string|null)[]`，装的是**实例 uid**， 于是武将页那一行只能印 `武器：eq-7f3a…` —— 把内部编号印给玩家（#255/#268/#278/#281 同族第五处）。 要带上名字与强化等级就得让每项是个对象，而 JSON Schema 表达不出「对象或 null」的数组元素 （`type` 数组只能列标量、生成器也没实现 `oneOf`）；把空槽写成「一个名字为空的 WornEquip」 又会把「没穿」和「穿了个没名字的」混成同一个值。所以改成**只列穿着的**：空槽＝数组里没有那一项， 客户端按 `slot` 落到四格里。原描述「定长数组是为了让客户端不必猜键名顺序」这条理由仍然成立 —— 每项自己写着槽位。 */
  equips: WornEquip[]
  baseAttrs: AttrTriple
  finalAttrs: AttrTriple
  /** 该武将的战力贡献，走 curve.HERO_GROWTH（幂律），仅用于展示与 B08 圈层匹配，不进战斗公式 */
  power: number
  /** 缘分对象武将 id；null 表示该武将没有缘分 */
  bondWith: string | null
}

/**
 * 一套编队预设（B06 §4：每队 3 名 = 主将 + 2 副将，可编 3 套）。
 */
export interface LineupView {
  presetIndex: number
  /** 主将 heroId；null 表示该位置空着 */
  main: string | null
  sub1: string | null
  sub2: string | null
  bonus: HeroBonus
  /** 已激活的缘分（成对同队才算）。给客户端做高亮与提示「再抽到 X 就能激活缘分」 */
  activeBonds: string[]
}

/**
 * GET /hero/list 响应体。
 */
export interface HeroListResp {
  heroes: HeroView[]
  lineups: LineupView[]
  /** 各稀有度的碎片持有量。碎片是道具（item 表的 item_mat_hero_frag_*），行里连名字一起下发 —— 这一行**包含余数为 0 的档**，客户端没法从背包 join 到名字（背包不列 0 余数的行），见 FragmentView 的理由 */
  fragments: FragmentView[]
  /** 当前带兵上限 = Σ上阵武将统帅值 × TROOP_PER_COMMAND + 科技加成（B05 §二） */
  troopCap: number
  /** 已占用的兵力。B05 第二步落地训练系统前恒为 0 */
  troopsInUse: number
  serverNow: number
}

/**
 * 道具数量条目。
 */
export interface ItemCount {
  itemId: string
  count: number
}

/**
 * 一档稀有度的碎片余额，**带中文名**。
 *
 * **为什么不复用 ItemCount**（原先这里写的正是「走 ItemCount 而不是新造一个类型」）：
 * 武将页那一行要把碎片展示给玩家，而 `ItemCount` 只有 `itemId` ⇒ 画面上出来的是
 * `item_mat_hero_frag_sr ×12` 这种行 id（#255 建筑名、#268 资源名、#278 技能名之后的同族第四处）。
 * **也不许客户端拿背包去 join**：本响应的碎片行**包含余数为 0 的档**（某一档没攒过也要让玩家看到 0），
 * 而背包只列余数大于 0 的行 —— join 的结果是「越没有越看不见名字」，症状正是退回印 id。
 *
 * **门槛与候选同批下发**（V03-d 第六条养成线）：只有余额的话界面只能说「你有 12 片」，
 * 说不出「还差 38 片就能合成典韦」—— 而后一句才是玩家按下按钮的理由。
 */
export interface FragmentView {
  /** item 表的行 id（item_mat_hero_frag_*） */
  itemId: string
  /** 道具中文名，服务端按 itemId 查 item 表的 name 列（与 RewardNames 对碎片的取法同源） */
  name: string
  count: number
  /** 合成这一档武将要消耗的碎片数，服务端按该稀有度查 hero_rarity 表的 composeFragment 列。 **为什么由服务端下发**：客户端一旦抄了 hero_rarity.json 就有了第二份真相，表改了「还差几片」会照旧说够。 算这句需要两个数 —— 门槛与余额，两个都必须来自本响应。 */
  composeFragment: number
  /** 这一档里**尚未拥有**、可用碎片合成的武将，顺序照 hero 表的行序。空数组＝没有可合成的 （该档武将全已拥有，或这一档根本没有武将）。 **为什么必须服务端给**：玩家没有的武将从不进 heroes 数组，客户端对「这一档有哪些武将」的唯一知情来源 就是 hero 表 —— 那正是不能抄的东西。已拥有的在服务端就排除掉，因为 `/hero/compose` 对已拥有直接拒绝 （不排除了会让玩家点一行注定被拒的武将）。 */
  candidates: ComposeCandidate[]
}

/**
 * 身上穿着的一件装备实例。**只列穿着的**，空槽不出现在数组里（见 HeroView.equips 的理由）。
 */
export interface WornEquip {
  /** 穿在哪个槽位。客户端按它把这一件落到四格里，所以数组可以是 0~4 项 */
  slot: EquipSlot
  /** 实例 uid —— 换装与卸下要按它发请求。**只用于请求，不许出现在画面上**（玩家读不懂一个内部编号） */
  uid: string
  /** equip 表的行 id（同一行可以有多件实例，套装与筛选按它查） */
  equipId: string
  /** 装备中文名，服务端按 equipId 查 equip 表的 name 列（#255 起那条「名字由服务端下发」的口径） */
  name: string
  /** 强化等级。玩家真正要看的「+2」是它，不是 uid */
  forgeLevel: number
}

/**
 * POST /hero/levelUp 请求体。经验书按 expItems 逐种消耗，服务端按 curve.HERO_LEVEL_EXP 连续升级（一次投喂多本可能连升数级）。
 */
export interface HeroLevelUpReq {
  requestId: string
  heroId: string
  expItems: ItemCount[]
}

/**
 * 所有养成操作（升级/升星/觉醒/技能/装备/合成）的统一响应：改完之后的武将状态。统一成一个类型是因为客户端对这六种操作的界面反馈完全相同（刷新属性条 + 播一次成长动效），拆成六个响应类型只会让客户端写六份一样的代码。
 */
export interface HeroGrowResp {
  hero: HeroView
  /** 实际消耗的道具/碎片。客户端据此播放扣减动画，不要自己算 */
  consumed: ItemCount[]
  serverNow: number
}

/**
 * 只带 heroId 的请求（升星、碎片合成）。
 */
export interface HeroIdReq {
  requestId: string
  heroId: string
}

/**
 * 带 heroId + itemId 的请求（觉醒、技能升级）。
 */
export interface HeroItemReq {
  requestId: string
  heroId: string
  itemId: string
  skillSlot: SkillSlot | null
}

/**
 * POST /hero/equip 请求体。equipUid 为 null 表示卸下该槽位（B06 验收 10：卸下后加成必须消失）。
 *
 * **为什么字段叫 equipUid 而不是 equipId**（B20 §五⑤）：装备现在是一件一个实例，"穿哪一件"与"穿哪一行"不是一回事。服务端仍然接受配置行 id（老客户端的写法，按"该行第一件未穿的"解析并记 ERROR 日志），但字段名按目标语义写 —— 名字留着旧语义，客户端改起来就没有方向。
 */
export interface HeroEquipReq {
  requestId: string
  heroId: string
  slot: EquipSlot
  equipUid: string | null
}

/**
 * POST /hero/lineup 请求体。三个位置都可为 null（表示空位），但主将为空时整队视为未编成。
 */
export interface SetLineupReq {
  requestId: string
  presetIndex: number
  main: string | null
  sub1: string | null
  sub2: string | null
}

/**
 * POST /hero/lineup 响应体。带 troopCap 是因为统帅值直接决定带兵上限（B06 验收 8），换队后客户端必须立刻刷新这个数字。
 */
export interface SetLineupResp {
  lineup: LineupView
  troopCap: number
  serverNow: number
}

/**
 * POST /gacha/draw 请求体。count 只能是 1 或 10（B06 §2）。补 requestId 是因为抽卡是不可重放的扣费操作，断网重放会白扣一次。
 */
export interface GachaDrawReq {
  requestId: string
  poolId: string
  count: number
}

/**
 * 一次抽取的结果。isPity 是合规字段（B06 禁止项：抽卡日志不要缺 isPity），必须下发到客户端并存进日志 —— 监管要能区分「正常抽到」与「保底触发」。
 */
export interface GachaResult {
  heroId: string
  name: string
  rarity: HeroRarity
  /** 是否首次获得。false 表示重复，已按 hero_rarity.dupFragment 转成碎片 */
  isNew: boolean
  /** 本次是否由保底触发（合规必需字段） */
  isPity: boolean
  /** 本次产生的碎片数；isNew=true 时为 0 */
  fragments: number
}

/**
 * POST /gacha/draw 响应体。seed 回传用于可复现（B06 验收 11：同 seed 同结果）与客服核查；两个保底计数器都回传，客户端要显示「还差几抽保底」。
 */
export interface GachaDrawResp {
  results: GachaResult[]
  /** 距上次出 SSR 已累计多少抽 */
  ssrPityCounter: number
  srPityCounter: number
  /** 本次重复武将转化的碎片总数 */
  fragmentsAwarded: number
  /** 以道具计价的池子（限定池）填这里，否则为 null。与 costResource 恰好一个非空。 */
  costItemId: string | null
  costCount: number
  seed: number
  serverNow: number
  /** 以资源计价的池子（新手池/标准池填 GOLD）填这里，否则为 null。与 costItemId 恰好一个非空 —— 两者都填或都不填都是配置错误，由 GachaConfigConsistencyTest 断言。 */
  costResource: string | null
}

/**
 * 概率面板的一行（B06 §2：客户端概率面板必须读同一份配置，禁止写死）。
 */
export interface GachaProbItem {
  heroId: string
  name: string
  rarity: HeroRarity
  /** 定点概率（×10000）。用定点数而不是 double（B06 禁止项：不要用 double 表示概率） */
  rateFixed: number
  /** 是否为当期 UP 武将 */
  isUp: boolean
}

/**
 * 保底规则。全部来自 gacha 表，客户端不得自行推算。
 */
export interface PityRule {
  /** 累计多少抽未出 SSR 则必出 */
  ssrPity: number
  srPity: number
  /** 限定池专用：连续多少次 SSR 非 UP 后，下一次 SSR 必为 UP。0 表示该池无 UP 保底（标准池） */
  ssrUpGuarantee: number
}

/**
 * GET /gacha/probability 响应体。disclosureText 必须原文展示（gacha 表已注明：不得删减、折叠或以图标替代）。
 */
export interface GachaProbResp {
  poolId: string
  name: string
  poolType: string
  /** 逐个武将的概率。限定池的 UP 武将单列，其余同稀有度武将平分该档剩余概率 */
  items: GachaProbItem[]
  /** 四档概率（SSR/SR/R/N），之和必须恰为 10000（B02 已把这条做成 CI 断言） */
  tierRates: TierRate[]
  pityRule: PityRule
  /** 合规公示原文，客户端必须原样展示 */
  disclosureText: string
  /** 以道具计价的池子（限定池）填这里，否则为 null。与 costResource 恰好一个非空。 */
  costItemId: string | null
  /** 单抽消耗 */
  costCount: number
  /** 该池的账号终身抽取次数上限；0 表示不限 */
  lifetimeLimit: number
  serverNow: number
  /** 以资源计价的池子（新手池/标准池填 GOLD）填这里，否则为 null。与 costItemId 恰好一个非空 —— 两者都填或都不填都是配置错误，由 GachaConfigConsistencyTest 断言。 */
  costResource: string | null
}

/**
 * 一档稀有度的概率。
 */
export interface TierRate {
  rarity: HeroRarity
  rateFixed: number
}

/**
 * 一个可用碎片合成的武将条目（合成后即获得该武将，B06 §1）。
 */
export interface ComposeCandidate {
  /** POST /hero/compose 要带的 heroId。 */
  heroId: string
  /** 武将中文名，服务端查 hero 表的 name 列（#255 建筑名、#268 资源名、#278 技能名、#281 碎片名同一路）。 **这一列是这一行存在的理由**：客户端只有 heroId 的话，候选行只能印 `hero_ssr_02` —— 服务端本来就握着名字，且「未拥有的武将」在别处一个字段都读不到。 */
  name: string
}
