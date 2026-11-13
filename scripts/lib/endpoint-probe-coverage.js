/**
 * 「客户端绑定路径有没有探针驱动」的单一真源。
 *
 * 为什么单独抽出来：报告器（`tools/report-endpoint-probe-coverage.mjs`）与门
 * （`scripts/check-probe-endpoint-coverage.sh`）必须用同一套谓词，否则「报告说 33 条、
 * 门却说别的数」会变成第二个真源 —— 本仓已经有太多这种分叉。
 *
 * 判据（故意做得很粗，粗才不会假精确）：把路径截到第一个动态段之前（`:` 或 `{`），
 * 在探针源码里搜这个字面量。搜不到 ⇒ 这条路径没有任何探针驱动。
 * 它**不**证明探针真的会打这条路径（那是运行时探针自己的事），只回答「有没有人碰过」。
 */
const fs = require('fs');
const path = require('path');

const SKIP_FILES = new Set(['report-endpoint-probe-coverage.mjs', 'verify-perf-runtime.mjs']);

function collectProbes(toolsDir) {
  const found = [];
  (function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.mjs')) found.push(full);
    }
  })(toolsDir);
  return found
    .filter((file) => !SKIP_FILES.has(path.basename(file)))
    .map((file) => ({
      file: path.relative(path.dirname(toolsDir), file).replace(/\\/g, '/'),
      text: fs.readFileSync(file, 'utf8'),
    }));
}

function literalPrefix(routePath) {
  const cut = routePath.search(/[:{]/);
  return cut < 0 ? routePath : routePath.slice(0, cut);
}

function classify(boundPaths, toolsDir) {
  if (!Array.isArray(boundPaths) || boundPaths.length === 0) {
    throw new Error('绑定路径清单为空：真源读不到时不能当"没东西要测"');
  }
  const probes = collectProbes(toolsDir);
  const driven = [];
  const undriven = [];
  for (const routePath of boundPaths) {
    const prefix = literalPrefix(routePath);
    const owners = probes.filter((probe) => probe.text.includes(prefix)).map((probe) => probe.file);
    if (owners.length === 0) undriven.push(routePath);
    else driven.push({ path: routePath, owners });
  }
  return { probes: probes.length, driven, undriven };
}

module.exports = { classify, collectProbes, literalPrefix };
