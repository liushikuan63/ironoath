/**
 * 「客户端绑定路径有没有探针驱动」的单一真源。
 *
 * 为什么单独抽出来：报告器（`tools/report-endpoint-probe-coverage.mjs`）与门
 * （`scripts/check-probe-endpoint-coverage.sh`）必须用同一套谓词，否则「报告说 33 条、
 * 门却说别的数」会变成第二个真源 —— 本仓已经有太多这种分叉。
 *
 * 判据（故意做得很粗，粗才不会假精确）：把路径截到第一个动态段之前（`:` 或 `{`），
 * 在探针源码**去掉注释之后**搜这个字面量。搜不到 ⇒ 这条路径没有任何探针驱动。
 *
 * 为什么必须去注释（#2522 现跑抓到）：`tools/verify-nation-live.mjs` 里有一句注释
 * 「没有 `GET /nation/relations`，这一屏就是旧数据」，于是 `/nation/relations` 被算成
 * 「有探针驱动」—— 可那份探针读的是 `/nation/diplomacy` 回的 `allRelations`，
 * 从来没过 relations 这条路径。一句解释性注释就能把覆盖面数字垫高，这正是本门要防的
 * 「数字好看、行为没验」。注释剥掉之后它回到无驱动名单（117/30 → 116/31）。
 *
 * 它**不**证明探针真的会打这条路径（那是运行时探针自己的事），只回答「有没有人碰过」；
 * 用 UI 点击驱动接口的探针仍须在源码里点到那条路径并把请求捞下来断言一次（#2521）。
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
      text: stripComments(fs.readFileSync(file, 'utf8')),
    }));
}

/**
 * 去掉块注释与行注释，保留 `https://` 这类不是注释的双斜线。
 * 只服务于「这段字在不在代码里」，不追求语法级准确。
 */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:\\])\/\/[^\n]*/g, '$1 ');
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

module.exports = { classify, collectProbes, literalPrefix, stripComments };
