#!/usr/bin/env node
/**
 * 职责：客户端视图里**声明**的 `on*` 回调，必须真的有人**调用**它 —— 只赋值不调用就是死按钮。
 *
 * 为什么开这道门（2026-10-07，台账 #770）：`GiftPopupView` 的 `onBuy` 声明在第 36 行、
 * `GameBootstrap.ts:1231` 也赋了值（`giftPopup.onBuy = productId => root.buyGift(productId)`），
 * 但**全仓没有任何一处调用它** ⇒ 玩家点「立即购买」什么都不会发生，整条 B19 支付链在玩家侧不可达。
 * 而当时 45 道静态门与 1031 项客户端单测全绿：
 *   - `check-client-send-paths` 数的是 GameApi 发送口的调用点，`createPayOrder` 的调用点在 AppRoot（作为 deps 传进流程类）；
 *   - `check-client-orphans` 数的是"文件有没有被 import"，那个视图文件当然被 import 了。
 * ⇒「判定写了没接上」的第三个层级（契约有列 → 流程带出 → **视图不画/不接**）此前没人守。
 *
 * 关键判据：**赋值 ≠ 调用**。
 *   声明：`  onBuy: ((productId: string) => void) | null = null`
 *   赋值：`giftPopup.onBuy = ...`            ← 不算调用
 *   调用：`this.onBuy?.(productId)` / `view.onBuy(x)`  ← 算
 *
 * 三条"命中数必须 > 0"的下限断言（防"扫空目录然后退 0"的假绿，同 check-package-path-predicates 的教训）：
 *   扫到的文件数、找到的声明数、白名单条目仍然有效。
 *
 * 可选输入口（**不设时与默认扫描完全一致**，check-gates-can-fail.sh 靠它们做零污染三读数）：
 *   DEADCALL_SCAN_DIRS   空格分隔的扫描根，默认 `client/assets/scripts`
 *   DEADCALL_ALLOW_EXTRA 追加一行白名单（`名字<TAB>理由`），只用于自测
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SCAN_DIRS = (process.env.DEADCALL_SCAN_DIRS || 'client/assets/scripts').split(/\s+/).filter(Boolean);
const SKIP_PATH_PARTS = [
  ['net', 'generated'],        // npm run gen 产出，回调形状由协议决定
  'tests',
];

function walk(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...walk(p));
    } else if (entry.name.endsWith('.ts')) {
      out.push(p);
    }
  }
  return out;
}

function skipped(file) {
  const seg = file.split(path.sep);
  for (const part of SKIP_PATH_PARTS) {
    if (Array.isArray(part)) {
      const i = seg.indexOf(part[0]);
      if (i >= 0 && seg[i + 1] === part[1]) return true;
    } else if (seg.includes(part)) {
      return true;
    }
  }
  return false;
}

const files = [];
for (const dir of SCAN_DIRS) {
  const abs = path.join(ROOT, dir);
  if (!fs.existsSync(abs)) {
    console.error(`[dead-callbacks][FAIL] 扫描根不存在：${dir}（fail-closed，不静默跳过）`);
    process.exit(1);
  }
  files.push(...walk(abs).filter((f) => !skipped(f)));
}
if (files.length === 0) {
  console.error('[dead-callbacks][FAIL] 一个 .ts 都没扫到 —— 谓词失效时判红而不是判绿');
  process.exit(1);
}

// 全仓文本一次读入：调用点可能出现在别的文件里（宿主程序化触发也算"接上了"）
const sources = new Map(files.map((f) => [f, fs.readFileSync(f, 'utf8')]));

/**
 * 声明形状：类字段 `onXxx: ((...) => ...) | null = null`。
 *
 * ⚠️ **必须要求结尾的 `| null = null`**：第一版只写 `^ {2,4}on(Name)\s*:\s*[(!]`，
 * 结果把跨行参数列表里的**方法参数名**当成了字段声明，一次误报 10 处
 * （`private button(name, text, x, y, enabled,\n  onClick: () => void): void {` ——
 * 续行正好落在 4 空格缩进上，而 `onClick` 是局部参数、压根不是宿主回调）。
 * 计数型谓词先做误报自查：这 10 条是抽看源码才发现的，不是把门判红当"抓到十个缺陷"。
 */
const DECL = /^ {2,4}on([A-Z][A-Za-z0-9]*)\s*:\s*\(.*\|\s*null\s*=\s*null\s*$/gm;
/** 调用形状：`.onXxx(` 或 `.onXxx?.(`（前面可以是 this/变量名/可选链）。 */
const callPattern = (name) => new RegExp('\\.on' + name + '\\s*(\\?\\.)?\\s*\\(');
/** 赋值形状（明确排除，避免把 `x.onBuy = ` 当成调用）。 */

const violations = [];
let declarations = 0;

for (const [file, src] of sources) {
  const names = new Set([...src.matchAll(DECL)].map((m) => m[1]));
  for (const name of names) {
    declarations += 1;
    let called = false;
    for (const [other, otherSrc] of sources) {
      if (callPattern(name).test(otherSrc)) {
        called = true;
        break;
      }
    }
    if (!called) {
      const line = src.split('\n')
        .findIndex((l) => new RegExp('^ {2,4}on' + name + '\\s*:\\s*\\(.*\\|\\s*null\\s*=\\s*null\\s*$').test(l)) + 1;
      violations.push({ file: path.relative(ROOT, file).replace(/\\/g, '/'), name, line });
    }
  }
}

if (declarations === 0) {
  console.error('[dead-callbacks][FAIL] 一条 on* 回调声明都没找到 —— 正则或扫描根不对，判红而不是判绿');
  process.exit(1);
}

// 白名单（文件名 <TAB> 回调名 <TAB> 理由）：与 check-client-orphans 同一条纪律，条目腐烂也要红
const ALLOWED = new Map();
for (const row of (process.env.DEADCALL_ALLOW_EXTRA || '').split('\n').filter(Boolean)) {
  const [file, name, reason] = row.split('\t');
  if (file && name) ALLOWED.set(file + '#' + name, reason || '');
}
const fsAllowed = path.join(ROOT, 'scripts', 'check-client-dead-callbacks.allowlist');
if (fs.existsSync(fsAllowed)) {
  for (const line of fs.readFileSync(fsAllowed, 'utf8').split('\n')) {
    if (!line.trim() || line.startsWith('#')) continue;
    const [file, name, reason] = line.split('\t');
    if (file && name) ALLOWED.set(file + '#' + name, reason || '');
  }
}

const real = [];
for (const v of violations) {
  const key = v.file + '#' + v.name;
  if (ALLOWED.has(key)) {
    ALLOWED.delete(key);   // 命中即消费：剩下的条目就是"已修却没删豁免"的腐烂
    continue;
  }
  real.push(v);
}

for (const [, reason] of ALLOWED) {
  if (reason === '__selftest__') continue;
  // 白名单腐烂不判红（避免与并行会话抢门），但一定印出来
  console.log('[dead-callbacks][NOTE] 白名单里有一条已经不再命中（可以删）：见 scripts/check-client-dead-callbacks.allowlist');
}

console.log(`[dead-callbacks] 扫 ${files.length} 个 .ts，声明 ${declarations} 条 on* 回调，未接 ${real.length} 条`);
if (real.length > 0) {
  console.error('[dead-callbacks][FAIL] 这些回调只有声明与赋值、没有任何调用点（画了按钮但点不动）：');
  for (const v of real) {
    console.error(`  ${v.file}:${v.line}  on${v.name}`);
  }
  console.error('  要么在视图里把玩家的输入接上（`this.onXxx?.(...)`），要么删掉这个回调与它的赋值 —— 别留着当装饰。');
  process.exit(1);
}
console.log('[dead-callbacks] 通过：每一条 on* 回调都有调用点');
