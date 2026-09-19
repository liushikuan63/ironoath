#!/usr/bin/env node
/**
 * 收口清单 #75 的横扫口径，固化成可复跑的工具。
 *
 * 口径：一个已实现的 public 方法，如果在**除声明文件之外**的所有 main 源码里都没有 `.name(` / `::name`
 * 出现，它就是"有实现、有测试、生产不会走到"。这一族的症状从来不是报错，而是"玩家点不到这件事"。
 *
 * 两条刻意的口径限制，都是为了不把结论读反：
 *  1) 只数跨文件调用 —— 类内自调用会被算成零调用（#76 实测到的假阳性），所以输出只是**候选清单**；
 *  2) 只数 main 源码 —— 测试调用不算，因为"只有测试在调"正是这一族的定义。
 *
 * 反空转下限：读不到的文件数、方法数一旦低于下限就直接退 2。
 * "一个都没匹配上"和"确实没有违规"必须是两个不同的退出码（#53、#64 各踩过一次）。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MODULES = ['game-common', 'game-config', 'game-core', 'game-battle', 'game-web'];
const MIN_FILES = 400;
const MIN_METHODS = 500;

function mainFiles() {
  const out = [];
  for (const m of MODULES) {
    const dir = path.join(ROOT, 'server', m, 'src', 'main', 'java');
    if (!fs.existsSync(dir)) continue;
    walk(dir, out);
  }
  return out;
}

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
}

/** 去掉注释与字符串字面量，免得把 javadoc 里的 {@code foo()} 或日志文案当成调用点。 */
function strip(src) {
  let out = '';
  let i = 0;
  let state = 'code';
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') { state = 'line'; i += 2; continue; }
      if (c === '/' && n === '*') { state = 'block'; i += 2; continue; }
      if (c === '"') { state = 'str'; i += 1; out += ' '; continue; }
      out += c;
      i += 1;
      continue;
    }
    if (state === 'line') {
      if (c === '\n') { state = 'code'; out += c; }
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') { state = 'code'; i += 2; continue; }
      out += c === '\n' ? '\n' : ' ';
      i += 1;
      continue;
    }
    if (state === 'str') {
      if (c === '\\') { i += 2; continue; }
      if (c === '"') { state = 'code'; }
      i += 1;
      continue;
    }
  }
  return out;
}

const DECL = /(?:public|protected)\s+(?:static\s+|final\s+|synchronized\s+|abstract\s+|default\s+)*[\w$<>\[\],.?\s&]+?\s+([\w$]+)\s*\(/g;

function declaredMethods(src) {
  const clean = strip(src);
  const names = new Set();
  let m;
  DECL.lastIndex = 0;
  while ((m = DECL.exec(clean))) {
    const name = m[1];
    if (/^(if|for|while|switch|catch|return|new|class|interface|enum|record)$/.test(name)) continue;
    names.add(name);
  }
  return names;
}

/** 一个文件里出现过的"被调用名"集合：`.name(` 与 `::name`。整个语料只扫一遍，不按方法数重复扫。
 *  刻意不写 `[.:]{1,2}...[(:]` 这种对称形式 —— 三元表达式 `a ? foo : bar` 会被它当成 foo 被调用，
 *  于是真缺口被算成"有人调"，而这类漏报正好是本工具唯一要防的失效方向。 */
const CALL = /\.([$\w]+)\s*\(|::\s*([$\w]+)/g;

function calledNames(src) {
  const set = new Set();
  let m;
  CALL.lastIndex = 0;
  while ((m = CALL.exec(src))) set.add(m[1] || m[2]);
  return set;
}

function main() {
  const files = mainFiles();
  if (files.length < MIN_FILES) {
    console.error(`[scan] 只读到 ${files.length} 个 main 源文件（下限 ${MIN_FILES}）—— 扫描器没看见代码，不是没有违规`);
    process.exit(2);
  }
  const calls = new Map();
  const declared = [];
  for (const f of files) {
    const raw = fs.readFileSync(f, 'utf8');
    const clean = strip(raw);
    calls.set(f, calledNames(clean));
    if (f.includes(`${path.sep}game-core${path.sep}`)) {
      const names = declaredMethods(clean);
      for (const name of names) declared.push({ file: f, name });
    }
  }
  if (declared.length < MIN_METHODS) {
    console.error(`[scan] 只解析到 ${declared.length} 个 public 方法声明（下限 ${MIN_METHODS}）—— 正则没匹配上，结论不可用`);
    process.exit(2);
  }

  const unused = [];
  for (const { file, name } of declared) {
    let hits = 0;
    for (const f of files) {
      if (f === file) continue;
      if (calls.get(f).has(name)) hits++;
    }
    if (hits === 0) unused.push({ name, file: path.relative(ROOT, file).replace(/\\/g, '/') });
  }

  const byFile = new Map();
  for (const u of unused) byFile.set(u.file, (byFile.get(u.file) || 0) + 1);

  console.log(`[scan] 主源码 ${files.length} 个文件，game-core 解析到 ${declared.length} 个 public 方法声明`);
  console.log(`[scan] 生产零调用点：${unused.length} 个（${byFile.size} 个文件），以下为候选清单（类内自调用会被误计）`);
  for (const [file, count] of [...byFile].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))) {
    const names = unused.filter((u) => u.file === file).map((u) => u.name).sort();
    console.log(`  ${String(count).padStart(3)}  ${file}`);
    console.log(`       ${names.join(', ')}`);
  }
  console.log('[scan] 本工具只出候选，每条都要读实现才能定性（#76 实测 4 个候选里只有 1 个是真缺口）。');
}

main();
