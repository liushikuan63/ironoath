/**
 * 判据：玩家资源条必须按**配置表顺序**渲染 —— 从建档、持久化到 API 快照，三处都不得让顺序被散列摆放打乱。
 *       盯的是 #617/#618 修掉的那句 `Map.copyOf`，以及它两侧的同一条链。
 *
 * 背景（收口清单 #616~#618）：`PlayerSave.resources()` 原本返回 `Map.copyOf(resources)`。
 * 那个实现是 `ImmutableCollections.MapN`，按 **SALT** 散列摆放键来防碰撞攻击，而 SALT 每个 JVM 进程
 * 都不同 ⇒ 迭代顺序**跨进程变、进程内恒定**。实测 8 次 JVM 启动出现 6 种顺序，根因复现器在
 * `tools/MapCopyOrderProof.java`。这条链一路传到 `/city/list` 的 `resources` 字段、再传到客户端资源条的
 * 格子顺序 ⇒ **玩家每次重新登录看到的资源排列可能不同**，而任何按「第 N 格」写死假设的量具都会时绿时红。
 *
 * 为什么 #618 的单测不够：它钉的是**进程内**顺序（4 条用例钉住覆盖写不重排键位）。而本条缺陷的
 * 特征恰恰是「进程内恒定、跨进程才变」—— 只在单进程里跑，用例永远是绿的，退回去也不红。
 * 跨进程那条当时只有一次性手工读数，没有任何东西盯着它，所以它会长回来。
 *
 * 本门的三条红法：
 *   ① 主判据：`PlayerSave.resources()` 的方法体里出现任一**破序**构造（`Map.copyOf` / `Map.of` /
 *      `Map.ofEntries` / `HashMap` / `ImmutableMap` / `Collectors.toMap|groupingBy` / `TreeMap`）。
 *   ② 邻接两跳：`PlayerInitService.createNewPlayer` 与 `PlayerDocumentMapper.toDocument|toDomain`
 *      也必须保序 —— 破序点从这三处任何一个冒出来，玩家看到的排列一样会变。
 *   ③ **量具没架对**：上面三处方法只要有一处定位不到（改名、改签名、挪文件），本门直接红。
 *      定位不到却报绿，是这道门最坏的失败方式 —— 它会把"方法不见了"伪装成"不变量成立"。
 *
 * 判据本身也有判据（收口清单 §本系列同族错误 的第 ② 条纪律）：每次跑，本门**先把 6 份合成夹具**
 * 灌进与真实扫描同一个 `judge()` 里，确认"破序⇒红、保序⇒绿、定位不到⇒红"这三条都成立，
 * 夹具与实现对不上就在最前面报红。夹具对不上时后面的扫描结果一律不作数。
 *
 * 覆盖面边界（别把绿字当包票）：本门只管**顺序**。`resources()` 是否还返回不可变视图、
 * 有没有把内部 Map 漏出去，是另一条不变量，本门不判。另外这三处方法是**整段**扫的，
 * 范围比资源链略宽（同一方法里别的快照字段若有破序构造也会红）—— 这是刻意保守：
 * 宁可多喊一声，也不要漏；而空 `Map.of()` 兜底已实测排除，不产生噪声。
 */
const fs = require('fs')

const NL = String.fromCharCode(10)

const FILES = {
  save: 'server/game-core/src/main/java/com/ironoath/core/player/PlayerSave.java',
  init: 'server/game-web/src/main/java/com/ironoath/web/service/PlayerInitService.java',
  mapper: 'server/game-web/src/main/java/com/ironoath/web/store/mongo/PlayerDocumentMapper.java',
}

/** 破序构造：出现任意一条就红，逐条写清为什么。 */
const BREAKING = [
  { re: /\bMap\s*\.\s*copyOf\b/, why: 'ImmutableCollections.MapN 按 SALT 散列摆放键，顺序跨进程变（#617 的原始病因）' },
  { re: /\bMap\s*\.\s*ofEntries\s*\(\s*[^)\s]/, why: 'Map.ofEntries 同样是散列摆放' },
  // Map.of() 的**空**重载不算：空 map 没有任何键位，迭代顺序就是空序列，不存在"乱序"。
  // 实测过这个假阳性——PlayerDocumentMapper.toDomain 里有四处 `java.util.Map.of()` 兜底空集合
  // （礼包 showsByGift / triggeredAt / purchasedCountByGift），与资源条毫无关系。
  { re: /\bMap\s*\.\s*of\s*\(\s*[^)\s]/, why: '非空 Map.of 是散列摆放' },
  { re: /\bnew\s+HashMap\s*</, why: 'HashMap 不保序' },
  { re: /\bImmutableMap\b/, why: 'Guava 的 ImmutableMap 不保序' },
  { re: /\bCollectors\s*\.\s*toMap\b/, why: 'Collectors.toMap 默认用 HashMap 收集，不保序' },
  { re: /\bCollectors\s*\.\s*groupingBy\b/, why: 'groupingBy 默认用 HashMap 收集，不保序' },
  { re: /\bTreeMap\b/, why: 'TreeMap 虽然跨进程确定，但排的是字典序而不是配置表顺序，玩家看到的排列仍然是错的' },
]

/** 去掉行注释、块注释与字符串/字符字面量 —— 判据只认代码，不认"提到"这两个词的文档。 */
function stripNonCode(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/"(?:\\.|[^"\\])*"/g, '""')
    .replace(/'(?:\\.|[^'\\])*'/g, "''")
}

/**
 * 从声明行起按花括号配平截出方法体。
 * @returns {string|null} null = 定位不到（调用方必须当成红，不能当成过）
 */
function extractBody(src, declRe) {
  const lines = src.split(/\r?\n/)
  const start = lines.findIndex((l) => declRe.test(stripNonCode(l)))
  if (start < 0) return null
  let depth = 0
  let opened = false
  const out = []
  for (let i = start; i < lines.length; i++) {
    const code = stripNonCode(lines[i])
    out.push(code)
    for (const ch of code) {
      if (ch === '{') { depth++; opened = true }
      else if (ch === '}') { depth-- }
    }
    // 必须在见过 '{' 之后才允许配平结束，否则一行声明（如 `void f();`）会被当成方法体
    if (opened && depth === 0) return out.join(NL)
  }
  return null
}

/**
 * 判一个方法体保不保序。真实扫描与合成夹具共用这一个函数 —— 这是"判据也有判据"的前提。
 * @returns {{red: boolean, reasons: string[]}}
 */
function judge(body) {
  if (body === null) return { red: true, reasons: ['量具没架对：定位不到这个方法（改名 / 改签名 / 挪文件都会这样）'] }
  const reasons = []
  for (const b of BREAKING) {
    if (b.re.test(body)) reasons.push(b.why)
  }
  return { red: reasons.length > 0, reasons }
}

// ---------- 判据自检：先证明尺子量得动，再去量 ----------
// 夹具喂的是**整段源码**而不是裸方法体：自检必须和真实扫描走同一条流水线
// （定位声明 → 剥注释与字面量 → 配平截体 → 判保序）。2026-10-02 立门时第一版夹具直接喂裸体，
// 立刻测出"剥注释"这一步在真实路径上才生效 —— 夹具绕过它，自检就成了摆设。
const SRC = (...bodyLines) => [
  'public class PlayerSave {',
  '    public Map<String, PlayerResourceState> resources() {',
  ...bodyLines.map((l) => '        ' + l),
  '    }',
  '}',
].join(NL)

const SAVE_RESOURCES_DECL = /public\s+Map\s*<\s*String\s*,\s*PlayerResourceState\s*>\s+resources\s*\(\s*\)/

const FIXTURES = [
  { name: 'Map.copyOf（#617 的原始病因）', src: SRC('return Map.copyOf(resources);'), wantRed: true },
  { name: 'new HashMap', src: SRC('return new HashMap<>(resources);'), wantRed: true },
  { name: 'Map.of', src: SRC('return Map.of("wood", a, "stone", b);'), wantRed: true },
  { name: 'java.util.Map.of() 空 map 兜底（无键可乱序，不该误红）', src: SRC('return other == null ? java.util.Map.of() : other;'), wantRed: false },
  { name: 'Collectors.toMap', src: SRC('return resources.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));'), wantRed: true },
  { name: 'TreeMap（跨进程确定但不是配置表顺序）', src: SRC('return new TreeMap<>(resources);'), wantRed: true },
  { name: 'LinkedHashMap 快照（当前实现）', src: SRC('return Collections.unmodifiableMap(new LinkedHashMap<>(resources));'), wantRed: false },
  { name: 'unmodifiableMap 直挂字段（同样保序，不该误红）', src: SRC('return Collections.unmodifiableMap(resources);'), wantRed: false },
  { name: '只有注释里提到 Map.copyOf（提到不算用）', src: SRC('// 这里曾经是 Map.copyOf，已改（#617）', 'return Collections.unmodifiableMap(new LinkedHashMap<>(resources));'), wantRed: false },
  { name: '只有块注释里提到 HashMap（提到不算用）', src: SRC('/* 早年是 new HashMap<>(resources) */', 'return Collections.unmodifiableMap(new LinkedHashMap<>(resources));'), wantRed: false },
  { name: '方法被改名（量具没架对必须红，不许报绿）', src: 'public class PlayerSave {' + NL + '    public Map<String, PlayerResourceState> resourceSnapshot() {' + NL + '        return Collections.unmodifiableMap(new LinkedHashMap<>(resources));' + NL + '    }' + NL + '}', wantRed: true },
]

const broken = []
for (const f of FIXTURES) {
  const got = judge(extractBody(f.src, SAVE_RESOURCES_DECL)).red
  if (got !== f.wantRed) broken.push(f.name + '（期望 ' + (f.wantRed ? '红' : '绿') + '，实测 ' + (got ? '红' : '绿') + '）')
}
if (broken.length > 0) {
  console.error('[check-resource-order-invariant][FAIL] 判据自检没过，下面这次扫描的结果一律不作数：')
  broken.forEach((b) => console.error('  · ' + b))
  console.error('  这**不是通过**，不许拿这条红字当"不变量成立"的证据。')
  process.exit(1)
}

// ---------- 真实扫描 ----------
const fails = []
const TARGETS = [
  {
    file: FILES.save,
    label: 'PlayerSave.resources()',
    decl: SAVE_RESOURCES_DECL,
    why: '这是 #617 的破序点，也是 /city/list → 客户端资源条的那一环',
  },
  {
    file: FILES.init,
    label: 'PlayerInitService.createNewPlayer()',
    decl: /(?:public|private|protected)\s+[\w.<>\[\], ]*\s+createNewPlayer\s*\(/,
    why: '建档侧：它决定了资源键的初始顺序，破了就是"新号玩家的排列不对"',
  },
  {
    file: FILES.mapper,
    label: 'PlayerDocumentMapper.toDocument()',
    decl: /(?:public|private|protected)\s+static\s+[\w.<>\[\], ]*\s+toDocument\s*\(/,
    why: '持久化侧（写）：它决定落到 Mongo 的字段顺序',
  },
  {
    file: FILES.mapper,
    label: 'PlayerDocumentMapper.toDomain()',
    decl: /(?:public|private|protected)\s+static\s+[\w.<>\[\], ]*\s+toDomain\s*\(/,
    why: '持久化侧（读）：它决定从 Mongo 读回来时的键位顺序',
  },
]

let checked = 0
for (const t of TARGETS) {
  if (!fs.existsSync(t.file)) {
    fails.push(t.label + '：文件不存在（' + t.file + '）')
    continue
  }
  const body = extractBody(fs.readFileSync(t.file, 'utf8'), t.decl)
  const v = judge(body)
  if (v.red) {
    fails.push(t.label + '：' + v.reasons.join('；') + ' —— ' + t.why)
  } else {
    checked++
  }
}

// 建档侧还要钉住"按配置表顺序遍历"这一条：保序容器 + 乱序数据源 = 顺序仍然是错的
if (fs.existsSync(FILES.init)) {
  const initSrc = fs.readFileSync(FILES.init, 'utf8')
  const initBody = extractBody(initSrc, /(?:public|private|protected)\s+[\w.<>\[\], ]*\s+createNewPlayer\s*\(/)
  if (initBody !== null && !/allResources\s*\(/.test(stripNonCode(initBody))) {
    fails.push('PlayerInitService.createNewPlayer()：没有遍历 configs.allResources() —— 容器保序但数据源不是配置表，顺序照样不对')
  } else if (initBody !== null) {
    checked++
  }
}

if (fails.length > 0) {
  console.error('[check-resource-order-invariant][FAIL] 资源顺序不变量被打破：')
  fails.forEach((f) => console.error('  · ' + f))
  console.error('')
  console.error('  玩家可见后果：资源条每次重新登录的排列可能不同，且任何按「第 N 格」写死假设的量具会时绿时红。')
  console.error('  修法：保序快照用 Collections.unmodifiableMap(new LinkedHashMap<>(...))；')
  console.error('  根因复现器 tools/MapCopyOrderProof.java（8 次 JVM 启动出现 6 种顺序）。')
  console.error('  先例与口径见收口清单 #616~#618。')
  process.exit(1)
}

console.log('[check-resource-order-invariant] ' + checked + ' 处资源快照全部保序（判据自检 ' + FIXTURES.length + ' 份夹具通过）。')
