#!/usr/bin/env node
/**
 * 客户端绑定路径的「探针覆盖面」审计（永远退 0；判据在 `scripts/check-probe-endpoint-coverage.sh`）。
 *
 * 为什么要有它：`tools/verify-perf-runtime.mjs` 只能量到「这一次真打出去的报文」，
 * 2026-11-13 现跑是 34 / 147（未量 115）。剩下那 115 条此前只有一句笼统的
 * 「写接口与需要前置状态的读接口」——`#2518` 把这句话拆开数过之后才知道：
 * 115 条里 84 条其实有探针驱动（只是这次没走到），真正一条探针都没有的是 31 条。
 *
 * 判据与门同源：`scripts/lib/endpoint-probe-coverage.js`。
 *
 * 用法：
 *   node tools/report-endpoint-probe-coverage.mjs
 *   PERF_LOG=D:/Java/maven/logs/perf-2518.log node tools/report-endpoint-probe-coverage.mjs
 *
 * 末行是机读形状，便于台账引用：
 *   [probe-coverage] 绑定=147 有探针驱动=114 无探针驱动=33 [本次量到=34 未量=115（其中无驱动=31）]
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const { clientBoundPaths } = require(path.join(ROOT, 'scripts/lib/endpoint-paths.js'));
const { classify } = require(path.join(ROOT, 'scripts/lib/endpoint-probe-coverage.js'));

const bound = clientBoundPaths();
let result;
try {
  result = classify(bound, path.join(ROOT, 'tools'));
} catch (error) {
  console.log(`[probe-coverage] 判据失效（不当作通过）：${error.message}`);
  process.exit(0);
}

// 给了 perf 日志就再报「本次量到 / 未量」两个集合。
// 注意 JSON 形状：`coverage.measuredPaths` 是**条数**（34），`coverage.neverMeasured` 才是清单。
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

console.log('# 客户端绑定路径的探针覆盖面');
console.log(`# 探针文件 ${result.probes} 份；绑定路径 ${bound.length} 条`);
console.log('');
console.log(`## 有探针驱动（${result.driven.length} 条）`);
for (const item of result.driven.sort((a, b) => a.path.localeCompare(b.path))) {
  console.log(`- ${item.path}  <- ${item.owners.length} 份：${item.owners.slice(0, 3).join(', ')}${item.owners.length > 3 ? ' …' : ''}`);
}
console.log('');
console.log(`## 没有任何探针驱动（${result.undriven.length} 条）`);
for (const routePath of [...result.undriven].sort((a, b) => a.localeCompare(b))) console.log(`- ${routePath}`);

let tail = `[probe-coverage] 绑定=${bound.length} 有探针驱动=${result.driven.length} 无探针驱动=${result.undriven.length}`;
if (measuredCount !== null && neverMeasured) {
  const undrivenNever = neverMeasured.filter((routePath) => result.undriven.includes(routePath));
  tail += ` 本次量到=${measuredCount} 未量=${neverMeasured.length}（其中无驱动=${undrivenNever.length}）`;
}
console.log('');
console.log(tail);
process.exit(0);
