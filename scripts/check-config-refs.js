// 职责：配置表之间的引用外键必须成立。三条规则，从窄到宽：
//   规则 1：*itemId 必须指向 item 表（原本只有这一条）。
//   规则 2：任何以 Id 结尾的引用字段（主键 id 除外）必须能在**全体表的 id 空间**里找到。
//   规则 3：`<前缀>Type` 与 `<前缀>Id` 成对时，值必须落在该类型声明的那张表里。
//           规则 2 查不出「rewardType=RESOURCE 而 rewardId 填了道具 id」—— 两边都存在，
//           只有语义错了，而语义错了的后果是玩家领到别的东西。
// 为什么不写死"哪张表引用哪张表"：新加一张奖励表就会悄悄绕过检查；按字段名通用识别才能兜住新增。
// 悬空外键的后果不是报错而是"发了个不存在的东西"：商店买回来一件背包里渲染不出名字的道具、
// 活动发下去玩家找不到 —— 都是只会被玩家当 bug 报上来的那种。
//
// 四条真踩过的坑写在这里防复发：
// ① 字段名匹配必须**忽略大小写** —— 第一版写的是 /ItemId$/，于是 11 处 shop.itemId
//    一个都没检查到，而检查还"通过"了。这种半瞎的绿灯比没有检查危险。
// ② 每张有引用的表都要报出贡献数，贡献为 0 就失败 —— 静默少一张表就是静默少一半检查。
// ③ 规则 3 只认 `<前缀>Type` 配 `<前缀>Id`，**不认裸 `type` 配主键 `id`**：building 与 item
//    自己的 `type=RESOURCE` 说的是"这一行是资源类建筑/资源包道具"，配上主键就会报出 9 条假失配。
// ④ 未登记的类型值是**失败**而不是跳过 —— 跳过等于给新类型开了一个静默的免检口子。
const fs = require('fs')
const path = require('path')

/* 可选参数：换一份配置目录跑（用来验证这份检查真的会红，而不必先弄脏仓库里的表）。 */
const DIR = process.argv[2] || 'contract/config'
const tables = {}
for (const f of fs.readdirSync(DIR)) {
  if (!f.endsWith('.json')) continue
  tables[f.slice(0, -5)] = (JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8')).rows) || []
}

if (!tables.item || tables.item.length === 0) {
  console.error('[check-config-refs][FAIL] item 表读不到或为空，检查无法进行（宁可失败也不空转通过）')
  process.exit(1)
}
const itemIds = new Set(tables.item.map(r => r.id))

/* 全体表的 id 空间：值 -> 它出现在哪些表里（规则 2 与规则 3 共用）。 */
const homeOf = new Map()
for (const [name, rows] of Object.entries(tables)) {
  for (const r of rows) {
    if (r && typeof r.id === 'string') {
      if (!homeOf.has(r.id)) homeOf.set(r.id, [])
      homeOf.get(r.id).push(name)
    }
  }
}
if (homeOf.size < 400) {
  console.error('[check-config-refs][FAIL] id 空间只有 ' + homeOf.size + ' 个，疑似表没读全（读表坏了会让所有外键都"查不到东西"）')
  process.exit(1)
}

/* 类型声明 -> 该类型 id 归属的表。null 表示"id 是自由标识，只要求非空"（PRIVILEGE 那类）。
   HERO_FRAGMENT 指向 **hero**：`HeroFragmentExtras.fragmentItemOf` 收的是武将行 id（按该武将的稀有度
   落到 item_mat_hero_frag_<rarity>），唯一调用方 `GachaAppService` 传的也是 `draw.heroId()`。
   这里曾经照着 `RewardType` 的旧注释写成 hero_rarity（那句写错了，本轮已改）——
   **指向错表的卡口会把正确的行判成违规**，那是检查被人为关掉的头号成因。 */
const TYPE_TO_TABLE = {
  RESOURCE: 'resource',
  ITEM: 'item',
  CHEST: 'item',
  HERO_FRAGMENT: 'hero',
  STAMINA: 'resource',
  PRIVILEGE: null,
  HERO: 'hero',
  EQUIP: 'equip',
  UNIT: 'unit',
  BUILDING: 'building',
  SKILL: 'skill',
  TECH: 'tech',
  MONSTER: 'mapmonster',
}

const bad = []
const perTable = new Map()
const refPerTable = new Map()
const typedPerTable = new Map()
const unknownTypes = new Set()
let typedPairs = 0

for (const [name, rows] of Object.entries(tables)) {
  let itemHits = 0
  let refHits = 0
  let typeHits = 0
  rows.forEach((r, idx) => {
    if (!r || typeof r !== 'object') return
    const keys = Object.keys(r)

    /* 规则 1：道具外键 */
    if (name !== 'item') {
      for (const [key, value] of Object.entries(r)) {
        if (!/itemid$/i.test(key)) continue
        if (value === null || value === undefined || value === '') continue
        itemHits += 1
        if (!itemIds.has(value)) {
          bad.push(`[规则1] ${name}[${idx}].${key} -> "${value}" 不在 item 表`)
        }
      }
    }

    for (const [key, value] of Object.entries(r)) {
      /* 规则 2：一切 *Id 引用（主键 id 本身不算引用） */
      if (key !== 'id' && /id$/i.test(key) && typeof value === 'string' && value !== '') {
        refHits += 1
        if (!homeOf.has(value)) {
          bad.push(`[规则2] ${name}[${idx}].${key} -> "${value}" 不在任何表的 id 列里`)
        }
      }

      /* 规则 3：<前缀>Type 与 <前缀>Id 成对 */
      if (!/type$/i.test(key)) continue
      const stem = key.slice(0, -4)
      if (stem.length === 0) continue
      const idKey = keys.find((k) => k !== key && new RegExp('^' + stem + 'id$', 'i').test(k))
      if (!idKey) continue
      const typeValue = r[key]
      const refValue = r[idKey]
      if (typeof typeValue !== 'string' || typeValue === '') continue
      const table = TYPE_TO_TABLE[typeValue.toUpperCase()]
      if (table === undefined) {
        unknownTypes.add(typeValue)
        continue
      }
      typeHits += 1
      typedPairs += 1
      if (typeof refValue !== 'string' || refValue === '') {
        bad.push(`[规则3] ${name}[${idx}].${key}=${typeValue} 要求 ${idKey} 非空`)
        continue
      }
      if (table === null) continue
      const homes = homeOf.get(refValue)
      if (!homes) {
        bad.push(`[规则3] ${name}[${idx}].${key}=${typeValue} 而 ${idKey}="${refValue}" 不存在`)
      } else if (!homes.includes(table)) {
        bad.push(`[规则3] ${name}[${idx}].${key}=${typeValue} 说这是 ${table} 表的行，`
          + `但 ${idKey}="${refValue}" 实际在 ${homes.join('/')} 表`)
      }
    }
  })
  if (itemHits > 0) perTable.set(name, itemHits)
  if (refHits > 0) refPerTable.set(name, refHits)
  if (typeHits > 0) typedPerTable.set(name, typeHits)
}

const sum = (m) => [...m.values()].reduce((a, b) => a + b, 0)
const show = (m) => [...m.entries()].sort().map(([t, n]) => `${t}=${n}`).join(', ')
console.log('[check-config-refs] 道具外键 ' + sum(perTable) + ' 处：' + show(perTable))
console.log('[check-config-refs] 引用字段 ' + sum(refPerTable) + ' 处 / ' + refPerTable.size
  + ' 张表，id 空间 ' + homeOf.size + ' 个')
console.log('[check-config-refs] 类型配对 ' + typedPairs + ' 处：' + show(typedPerTable))

const failures = []
if (perTable.size < 2) {
  failures.push('只在一两张表里找到道具引用，疑似识别规则失效（检查大小写）')
}
if (refPerTable.size < 5 || sum(refPerTable) < 50) {
  failures.push('规则 2 只覆盖到 ' + refPerTable.size + ' 张表 / ' + sum(refPerTable)
    + ' 处引用（门槛 5 张表、50 处）—— 覆盖数掉下来通常意味着字段名规则又写错了')
}
if (unknownTypes.size > 0) {
  failures.push('出现未登记的类型值：' + [...unknownTypes].join('、')
    + '。请把它加进 TYPE_TO_TABLE（写明它的 id 归属哪张表，或 null 表示自由标识），不要靠跳过它变绿')
}
if (bad.length > 0) {
  failures.push('' + bad.length + ' 条引用不成立')
}
if (failures.length > 0) {
  console.error('[check-config-refs][FAIL]')
  for (const f of failures) console.error('   - ' + f)
  for (const b of bad) console.error('   * ' + b)
  process.exit(1)
}
console.log('[check-config-refs] 所有配置引用都指向真实存在的行，且类型声明与实际归属一致。')
