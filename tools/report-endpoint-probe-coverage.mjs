#!/usr/bin/env node
/**
 * 客户端绑定路径的「探针覆盖面」静态审计（永远退 0；判据留在台账与后续门）。
 *
 * 为什么要有它：`tools/verify-perf-runtime.mjs` 只能量到「这一次真打出去的报文」，
 * 2026-11-13 现跑是 34 / 147（未量 115）。剩下那 115 条此前只有一句笼统的
 * 「写接口与需要前置状态的读接口」——到底哪些是**没有任何探针驱动**、哪些只是
 * **这次没走到**，谁也说不清。本报告先把静态那一半数清楚。
 *
 * 用法：
 *   node tools/report-endpoint-probe-coverage.mjs
 *   PERF_LOG=D:/Java/maven/logs/perf-2518.log node tools/report-endpoint-probe-coverage.mjs
 *
 * 末行是机读形状，便于台账引用：
 *   [probe-coverage] 绑定=147 有探针驱动=N 无探针驱动=M 本次量到=K 未量=L（其中无驱动=M'）
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const { clientBoundPaths } = require(path.join(ROOT, 'scripts/lib/endpoint-paths.js'));

// 1) 客户端绑定的路径（与 CI 卡口同一份真源）
const bound = clientBoundPaths();
if (!Array.isArray(bound) || bound.length === 0) {
  console.log('[probe-coverage] 绑定=0：clientBoundPaths() 读不到，判据失效（不当作通过）');
  process.exit(0);
}

// 2) 全部探针（tools 下两级的 .mjs），排除本报告与 perf 自己
const probes = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (entry.name.endsWith('.mjs')) probes.push(full);
  }
})(path.join(ROOT, 'tools'));

const skip = new Set(['report-endpoint-probe-coverage.mjs', 'verify-perf-runtime.mjs']);
const texts = probes
  .filter((file) => !skip.has(path.basename(file)))
  .map((file) => ({ file: path.relative(ROOT, file).replace(/\\/g, '/'), text: fs.readFileSync(file, 'utf8') }));

function literalPrefix(p) {
  const cut = p.search(/[:{]/);
  return cut < 0 ? p : p.slice(0, cut);
}

const driven = [];
const undriven = [];
for (const p of bound) {
  const prefix = literalPrefix(p);
  const owners = texts.filter((probe) => probe.text.includes(prefix)).map((probe) => probe.file);
  if (owners.length === 0) undriven.push(p);
  else driven.push({ path: p, owners });
}

// 3) 给了 perf 日志就再报「本次量到 / 未量」两个集合
// 注意 JSON 的形状：`coverage.measuredPaths` 是**条数**（34），`coverage.neverMeasured` 才是路径清单。
// 早先按数组去 new Set() 会抛 `number 34 is not iterable` —— 两种形状都认，别猜。
let measuredCount = null;
let neverMeasured = null;
if (process.env.PERF_LOG && fs.existsSync(process.env.PERF_LOG)) {
  const log = fs.readFileSync(process.env.PERF_LOG, 'utf8');
  const start = log.lastIndexOf('\n{\n');
  if (start >= 0) {
    try {
      const json = JSON.parse(log.slice(start + 1));
      const coverage = json.coverage ?? {};
      if (Array.isArray(coverage.measuredPaths)) measuredCount = coverage.measuredPaths.length;
      else if (typeof coverage.measuredPaths === 'number') measuredCount = coverage.measuredPaths;
      neverMeasured = Array.isArray(coverage.neverMeasured) ? coverage.neverMeasured : null;
    } catch (error) {
      console.log(`[probe-coverage] 提示：perf 日志里的 JSON 解析失败（${process.env.PERF_LOG} 第 ${start} 字节起：${String(error.message).slice(0, 80)}），本次只给静态覆盖面`);
    }
  }
}

const sorted = (list) => [...list].sort((a, b) => a.localeCompare(b));
console.log('# 客户端绑定路径的探针覆盖面');
console.log(`# 探针文件 ${texts.length} 份；绑定路径 ${bound.length} 条`);
console.log('');
console.log(`## 有探针驱动（${driven.length} 条）`);
for (const item of driven.sort((a, b) => a.path.localeCompare(b.path))) {
  console.log(`- ${item.path}  <- ${item.owners.length} 份：${item.owners.slice(0, 3).join(', ')}${item.owners.length > 3 ? ' …' : ''}`);
}
console.log('');
console.log(`## 没有任何探针驱动（${undriven.length} 条）`);
for (const p of sorted(undriven)) console.log(`- ${p}`);

let tail = `[probe-coverage] 绑定=${bound.length} 有探针驱动=${driven.length} 无探针驱动=${undriven.length}`;
if (measuredCount !== null && neverMeasured) {
  const undrivenNever = neverMeasured.filter((p) => undriven.includes(p));
  tail += ` 本次量到=${measuredCount} 未量=${neverMeasured.length}（其中无驱动=${undrivenNever.length}）`;
}
console.log('');
console.log(tail);
process.exit(0);
