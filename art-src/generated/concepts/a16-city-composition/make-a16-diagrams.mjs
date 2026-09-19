/**
 * 职责：从**一张锚点表**生成 A16 的两张交付图（拆层示意 + 建筑热区/道路图）。
 * 依赖：node（无第三方）。
 *
 * <p>为什么用脚本生成而不是手画 SVG：热区图的价值全在"36 个格位一个不多一个不少、
 * 且每个格位只属于一个区"。手画会数错，而数错的图恰好是这轮最不该有的东西 ——
 * 规格 §3.3 明确禁止"把 36 个锚点均匀排成棋盘再称为城景化"，
 * 判据就得能被机器复算。脚本里那三条自检（36 个、id 唯一、每区有锚点）不通过就直接非 0 退出。
 *
 * <p>坐标是**显示投影**，不是服务器坐标：`gridX/gridY` 仍是 6×6 逻辑格，
 * 这里的 anchorX/anchorY 只是那一格在场景里的落地点。
 */
import { writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const OUT = dirname(fileURLToPath(import.meta.url))

/** 与 `client/assets/scripts/game/city/CityPanel.ts` 的 CITY_GRID_WIDTH/HEIGHT 同源，改了要一起改。 */
const GRID_W = 6
const GRID_H = 6

/**
 * 五个区。`parcel` 是"这块地长什么样"（地坪/路网/装饰），**不是**"这块地能建什么"：
 * 服务端允许任何已解锁建筑落在任何空格，所以区不否决建筑，只决定它周围的地面与路。
 */
const DISTRICTS = {
  crown: { name: '王庭高地', color: '#7a5a22', ground: '#3a2f1e' },
  civic: { name: '行政与民生区', color: '#3f5a6b', ground: '#26333c' },
  military: { name: '城门与军事区', color: '#6b2f2a', ground: '#33211f' },
  suburb: { name: '城郊生产带', color: '#3f5c38', ground: '#242e21' },
  wall: { name: '城郭与通道', color: '#555560', ground: '#2a2a30' },
}

/**
 * 36 格 → 锚点。anchorX/anchorY 是**非均匀**的场景落点（单位：与构图稿同比例的 1000×563 视图坐标），
 * w/h 是脚印宽深（主堡最大、生产带最扁），road 说明它接哪条路。
 * 行 0 是画面最北（远山），行 5 是城门一侧。
 */
const ANCHORS = [
  // 北坡生产带（远、小、扁）
  [0, 0, 'suburb', 132, 118, 96, 46, '北坡小道'],
  [1, 0, 'suburb', 268, 104, 104, 48, '北坡小道'],
  [2, 0, 'wall', 402, 92, 88, 40, '北垣步道'],
  [3, 0, 'wall', 556, 92, 88, 40, '北垣步道'],
  [4, 0, 'suburb', 700, 108, 112, 50, '东田埂'],
  [5, 0, 'suburb', 858, 122, 104, 48, '东田埂'],
  // 王庭高台（画面中上，最高、占地最大）
  [0, 1, 'suburb', 118, 208, 100, 52, '北坡小道'],
  [1, 1, 'crown', 250, 196, 112, 58, '台基西阶'],
  [2, 1, 'crown', 400, 176, 150, 76, '主堡前庭'],
  [3, 1, 'crown', 566, 176, 150, 76, '主堡前庭'],
  [4, 1, 'crown', 716, 198, 112, 58, '台基东阶'],
  [5, 1, 'suburb', 868, 220, 100, 52, '东田埂'],
  // 台基下沿 + 广场北缘
  [0, 2, 'wall', 112, 296, 92, 48, '西墙根'],
  [1, 2, 'civic', 244, 288, 116, 60, '学院支路'],
  [2, 2, 'crown', 392, 282, 128, 62, '台阶口'],
  [3, 2, 'crown', 556, 282, 128, 62, '台阶口'],
  [4, 2, 'civic', 712, 292, 116, 60, '使馆大道'],
  [5, 2, 'wall', 872, 302, 92, 48, '东墙根'],
  // 市集广场与主轴
  [0, 3, 'military', 116, 372, 100, 52, '校场便道'],
  [1, 3, 'civic', 250, 372, 118, 60, '医院小巷'],
  [2, 3, 'civic', 398, 366, 122, 62, '广场西廊'],
  [3, 3, 'civic', 560, 366, 122, 62, '广场东廊'],
  [4, 3, 'civic', 716, 372, 118, 60, '仓储车道'],
  [5, 3, 'wall', 876, 378, 92, 48, '东墙根'],
  // 军事区（城门两侧，方正、有围栏）
  [0, 4, 'military', 122, 444, 104, 54, '箭场长道'],
  [1, 4, 'military', 256, 444, 116, 58, '营区主道'],
  [2, 4, 'military', 404, 448, 120, 60, '营区主道'],
  [3, 4, 'military', 562, 448, 120, 60, '营区主道'],
  [4, 4, 'civic', 718, 444, 116, 58, '仓储车道'],
  [5, 4, 'wall', 878, 450, 92, 48, '东墙根'],
  // 城门与吊桥（画面最下，出征入口）
  [0, 5, 'suburb', 128, 512, 96, 44, '西坡矿道'],
  [1, 5, 'military', 262, 516, 104, 46, '马厩围场'],
  [2, 5, 'wall', 410, 528, 108, 40, '门洞西侧'],
  [3, 5, 'wall', 560, 528, 108, 40, '门洞东侧'],
  [4, 5, 'suburb', 716, 516, 104, 46, '东坡矿道'],
  [5, 5, 'suburb', 872, 512, 96, 44, '东坡矿道'],
].map(([col, row, district, x, y, w, h, road]) => ({
  col, row, district, x, y, w, h, road,
  anchorId: `p${row}${col}`,
}))

/** 主轴线：城门 → 广场 → 台阶 → 主堡。构图上必须一眼读得出来。 */
const AXIAL_ROAD = [[486, 563], [486, 500], [478, 430], [474, 356], [476, 300], [482, 246], [484, 176]]
/** 环城墙步道（示意）：让"城"有边界，而不是六块浮地。 */
const RING = [[70, 250], [150, 120], [420, 60], [760, 70], [930, 170], [945, 350], [905, 480], [620, 552], [330, 552], [90, 470]]

const esc = (text) => String(text).replace(/[&<>"]/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

function selfCheck() {
  const problems = []
  if (ANCHORS.length !== GRID_W * GRID_H) {
    problems.push(`锚点数 ${ANCHORS.length} ≠ ${GRID_W}×${GRID_H}`)
  }
  const ids = new Set(ANCHORS.map((a) => a.anchorId))
  if (ids.size !== ANCHORS.length) {
    problems.push(`anchorId 有重复：${ANCHORS.length - ids.size} 个`)
  }
  for (const key of Object.keys(DISTRICTS)) {
    if (!ANCHORS.some((a) => a.district === key)) {
      problems.push(`区 ${key} 一个锚点都没有`)
    }
  }
  const seen = new Set(ANCHORS.map((a) => `${a.col},${a.row}`))
  for (let row = 0; row < GRID_H; row++) {
    for (let col = 0; col < GRID_W; col++) {
      if (!seen.has(`${col},${row}`)) {
        problems.push(`格位 (${col},${row}) 没有锚点 —— 城景化会漏掉这一格`)
      }
    }
  }
  // 反棋盘判据：同一行里相邻锚点的水平间距不能全相等
  for (let row = 0; row < GRID_H; row++) {
    const xs = ANCHORS.filter((a) => a.row === row).map((a) => a.x).sort((p, q) => p - q)
    const gaps = xs.slice(1).map((x, i) => Math.round(x - xs[i]))
    if (new Set(gaps).size === 1) {
      problems.push(`第 ${row} 行的锚点间距完全相等（${gaps[0]}）—— 这就是规格禁止的"均匀排成棋盘"`)
    }
  }
  return problems
}

function hotZonesSvg() {
  const parts = []
  parts.push(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 583" width="2000" height="1166" font-family="sans-serif">`)
  parts.push('<rect width="1000" height="583" fill="#141210"/>')
  parts.push(`<polygon points="${RING.map(([x, y]) => `${x},${y}`).join(' ')}" fill="none" stroke="#555560" stroke-width="3" stroke-dasharray="14 8" opacity="0.75"/>`)
  parts.push(`<text x="16" y="574" fill="#8b8b96" font-size="12">虚线 = 城郭边界（静态舞台，不代表已建 wall）</text>`)
  // 各区地坪
  for (const [key, d] of Object.entries(DISTRICTS)) {
    const cells = ANCHORS.filter((a) => a.district === key)
    for (const a of cells) {
      parts.push(`<ellipse cx="${a.x}" cy="${a.y + a.h / 2}" rx="${a.w / 2 + 8}" ry="${a.h / 2 + 8}" fill="${d.ground}" opacity="0.85"/>`)
    }
  }
  // 主轴路
  parts.push(`<polyline points="${AXIAL_ROAD.map(([x, y]) => `${x},${y}`).join(' ')}" fill="none" stroke="#b9a077" stroke-width="13" stroke-linecap="round" opacity="0.55"/>`)
  parts.push(`<polyline points="${AXIAL_ROAD.map(([x, y]) => `${x},${y}`).join(' ')}" fill="none" stroke="#e6d3a8" stroke-width="2" stroke-dasharray="9 11" opacity="0.7"/>`)
  // 热区（脚印多边形）+ 标注
  for (const a of ANCHORS) {
    const d = DISTRICTS[a.district]
    const top = a.y - a.h / 2
    const pts = [
      [a.x - a.w / 2, a.y], [a.x, top], [a.x + a.w / 2, a.y], [a.x, a.y + a.h],
    ].map(([x, y]) => `${x},${y}`).join(' ')
    parts.push(`<polygon points="${pts}" fill="${d.color}" fill-opacity="0.34" stroke="${d.color}" stroke-width="1.6"/>`)
    parts.push(`<text x="${a.x}" y="${a.y + 4}" fill="#f0e6cf" font-size="11" text-anchor="middle">${esc(a.anchorId)}</text>`)
    parts.push(`<text x="${a.x}" y="${a.y + 17}" fill="#c9bfa6" font-size="8.5" text-anchor="middle">g${a.col},${a.row}</text>`)
  }
  // 图例
  let ly = 20
  parts.push('<text x="16" y="16" fill="#f0e6cf" font-size="14">A16 · 建筑热区与道路图（36 格 → 非均匀锚点；区只决定地面与路，不否决可建类型）</text>')
  for (const [key, d] of Object.entries(DISTRICTS)) {
    parts.push(`<rect x="16" y="${ly}" width="13" height="13" fill="${d.color}"/>`)
    parts.push(`<text x="35" y="${ly + 11}" fill="#d8cfb8" font-size="11.5">${esc(d.name)} · ${key} · ${ANCHORS.filter((a) => a.district === key).length} 格</text>`)
    ly += 17
  }
  parts.push(`<text x="640" y="574" fill="#9a927e" font-size="12">主轴：城门 → 市集广场 → 台基台阶 → 主堡（视线自下而上）</text>`)
  parts.push('</svg>')
  return `${parts.join('\n')}\n`
}

function layersSvg() {
  const LAYERS = [
    ['L0 远景', '山脊/远村/坡地，只给纵深与尺度参照', '#2b3a4a'],
    ['L1 地表与道路', '地坪、主轴、支路、田块畦纹（图案属于地表，不成棋盘）', '#3a3226'],
    ['L2 后侧城墙', '北垣与东西墙根，被建筑压住的部分不画', '#4a4a55'],
    ['L3 建筑阴影', '统一左上光源 ⇒ 阴影一律落在右下', '#241f1a'],
    ['L4 建筑主体', '15 类功能楼，按基座落地点（不是图片中心）做深度排序', '#6b5a3a'],
    ['L5 前侧城墙与前景', '近景墙可淡化/避让，绝不抢触摸', '#55555f'],
    ['L6 状态标识', '脚手架、可收取、暂停、选中光圈 —— 程序绘制优先', '#8a6b2f'],
    ['L7 合法空地提示', '仅建造模式下显示柔和轮廓', '#4f6b45'],
    ['L8 HUD 与详情', '资源条/队列条/详情卡沿用现有资产，主堡轮廓不得被截断', '#6b3a35'],
  ]
  const parts = []
  parts.push(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 583" width="2000" height="1166" font-family="sans-serif">`)
  parts.push('<rect width="1000" height="583" fill="#141210"/>')
  parts.push('<text x="16" y="20" fill="#f0e6cf" font-size="14">A16 · 拆层示意（由后向前，每层单独交付；底图里烘焙功能楼 = 重影 + 无法表达"未建"）</text>')
  const top = 44
  const step = 58
  LAYERS.forEach(([name, note, color], i) => {
    const y = top + i * step
    const x = 40 + i * 62
    parts.push(`<g transform="translate(${x},${y})">`)
    parts.push(`<rect x="0" y="0" width="380" height="42" rx="4" fill="${color}" stroke="#0e0d0c" stroke-width="1.5"/>`)
    parts.push(`<text x="12" y="19" fill="#f4ead2" font-size="13">${esc(name)}</text>`)
    parts.push(`<text x="12" y="34" fill="#cfc4ac" font-size="10">${esc(note)}</text>`)
    parts.push('</g>')
  })
  parts.push(`<text x="470" y="${top + 40}" fill="#9a927e" font-size="11.5">遮挡规则</text>`)
  const rules = [
    '· 同层按基座落地点排序，不按图片中心',
    '· 高塔可视觉压住后方楼，但不能截断它唯一的可点入口',
    '· 透明留白不参与命中：热区 = 脚印多边形，不是矩形图片',
    '· 近景墙与前景必须避开可交互区，否则"点不动"看起来像卡死',
    '· 背景与建筑分开出图：功能建筑不许烘焙进底图',
    '· 水面/断崖这类装饰格不响应建造，建造模式下也不给轮廓',
  ]
  rules.forEach((r, i) => {
    parts.push(`<text x="470" y="${top + 62 + i * 19}" fill="#bdb39c" font-size="11">${esc(r)}</text>`)
  })
  parts.push(`<text x="470" y="${top + 62 + rules.length * 19 + 10}" fill="#8f8770" font-size="10.5">层数与顺序取自 内城场景美术_VibeCoding规格.md §3.1；改层要同步改本图与 §3.1。</text>`)
  parts.push('</svg>')
  return `${parts.join('\n')}\n`
}

const problems = selfCheck()
if (problems.length > 0) {
  for (const p of problems) {
    console.log(`[a16][FAIL] ${p}`)
  }
  console.log('[a16] 锚点表不自洽，图未生成')
  process.exit(1)
}

writeFileSync(join(OUT, 'a16-hotzones.svg'), hotZonesSvg())
writeFileSync(join(OUT, 'a16-layers.svg'), layersSvg())

// `--table`：把同一张锚点表打成 Markdown，贴进交付文档用。
// 图与表出自同一份数据，不会出现"图改了、表还是旧的"那种两份真相。
if (process.argv.includes('--table')) {
  const rows = ANCHORS.map((a) => `| ${a.anchorId} | (${a.col}, ${a.row}) | ${DISTRICTS[a.district].name} · ${a.district} | ${a.w}×${a.h} | ${a.road} |`)
  console.log('| anchorId | 逻辑格位 (gridX, gridY) | 所在区 | 脚印（显示单位） | 接的道路 |\n|---|---|---|---|---|')
  console.log(rows.join('\n'))
}
console.log(`[a16] OK：${ANCHORS.length} 个锚点、${Object.keys(DISTRICTS).length} 个区、格位全覆盖、无等距行`)
console.log('[a16] 产出 a16-hotzones.svg / a16-layers.svg')
