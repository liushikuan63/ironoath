#!/usr/bin/env node
/**
 * 身份丢弃扫描：一个方法**签名里收了 playerId，方法体里却一次都没用它**。
 *
 * 为什么单独立这一条：所有端点的身份都是框架注入后当第一个参数传下去的，于是"忘了做归属校验"
 * 在编译期、类型检查、契约同步、乃至单测（夹具常常只有一个玩家）里都看不见。
 * 而它的运行时表现是另一个人拿着自己的登录态、把别人的资源 id 填进请求体就能读到、改到 ——
 * 一条日志都不会有。签名收身份而体不用，等于**身份在这条路径上被静默丢弃**。
 *
 * 口径（宁可漏报也不能自欺）：
 *  - 只判"体内零次出现"，不判"出现但没有用于查询" —— 后者要靠读代码，本工具只出候选；
 *  - 接口/抽象方法（无体）与构造器不计入；
 *  - 注释与字符串先剥掉，所以"只在日志文案里提 playerId"会被正确算成没用它？不会 ——
 *    LOG 里传的 `playerId` 是**表达式**而非文案，仍算使用；因此本工具会把"只打进日志"放过，
 *    这一条是刻意的局限，写在输出里而不是假装没有。
 *
 * 反空转下限：解析到的方法数一旦低于下限直接退 2（"没匹配上"和"没有违规"必须是两个退出码）。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MODULES = ['game-common', 'game-config', 'game-core', 'game-battle', 'game-web'];
const IDENTITY = /\b(String|long|Long)\s+(playerId|ownerId|targetPlayerId|actorPlayerId)\b/;
const MODIFIERS = /\b(public|protected|private)\b/;
const MIN_METHODS = 1500;

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
}

function sources() {
  const out = [];
  for (const m of MODULES) {
    const dir = path.join(ROOT, 'server', m, 'src', 'main', 'java');
    if (fs.existsSync(dir)) walk(dir, out);
  }
  return out;
}

/** 去注释与字符串，但用空格替换以保持下标不漂移（行号由下标现算）。 */
function strip(src) {
  const out = src.split('');
  let state = 'code';
  let i = 0;
  while (i < out.length) {
    const c = out[i];
    const n = out[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') { state = 'line'; i += 2; out[i - 2] = ' '; out[i - 1] = ' '; continue; }
      if (c === '/' && n === '*') { state = 'block'; i += 2; out[i - 2] = ' '; out[i - 1] = ' '; continue; }
      if (c === '"') { state = 'str'; out[i] = ' '; i += 1; continue; }
      i += 1;
      continue;
    }
    if (state === 'line') {
      if (c === '\n') { state = 'code'; i += 1; continue; }
      out[i] = ' ';
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') { state = 'code'; out[i] = ' '; out[i + 1] = ' '; i += 2; continue; }
      if (c !== '\n') out[i] = ' ';
      i += 1;
      continue;
    }
    if (c === '\\') { out[i] = ' '; out[i + 1] = ' '; i += 2; continue; }
    if (c === '"') { state = 'code'; }
    if (c !== '\n') out[i] = ' ';
    i += 1;
  }
  return out.join('');
}

function matchPair(text, openIdx, open, close) {
  let depth = 0;
  for (let i = openIdx; i < text.length; i++) {
    if (text[i] === open) depth++;
    else if (text[i] === close) { depth--; if (depth === 0) return i; }
  }
  return -1;
}

const SIG = /\b([\w$]+)\s*\(/g;

function methods(file, text) {
  const out = [];
  let m;
  SIG.lastIndex = 0;
  while ((m = SIG.exec(text))) {
    const name = m[1];
    if (/^(if|for|while|switch|catch|return|new|record|class|interface|enum|assert|throw|super|this)$/.test(name)) continue;
    const openParen = text.indexOf('(', m.index + name.length);
    const closeParen = matchPair(text, openParen, '(', ')');
    if (closeParen < 0) continue;
    const params = text.slice(openParen + 1, closeParen);
    // 方法声明：参数表之后到下一个 `{` 或 `;` 之间只允许 throws / 空白 / 注解
    let j = closeParen + 1;
    let bodyStart = -1;
    let isInterfaceMethod = false;
    while (j < text.length && j < closeParen + 400) {
      const ch = text[j];
      if (ch === '{') { bodyStart = j; break; }
      if (ch === ';') { isInterfaceMethod = true; break; }
      if (!/[\s\w$.<>,\[\]]/.test(ch)) break;
      j++;
    }
    if (bodyStart < 0) continue;
    const head = text.slice(Math.max(0, text.lastIndexOf('\n', m.index) + 1), m.index);
    if (!MODIFIERS.test(head)) continue;
    const bodyEnd = matchPair(text, bodyStart, '{', '}');
    if (bodyEnd < 0) continue;
    const body = text.slice(bodyStart + 1, bodyEnd);
    out.push({ name, params, body, line: text.slice(0, m.index).split('\n').length, abstract: isInterfaceMethod });
  }
  return out;
}

function main() {
  const files = sources();
  let total = 0;
  let skippedNoise = 0;
  const hits = [];
  for (const f of files) {
    const generated = /[\\/]generated[\\/]/.test(f);
    const text = strip(fs.readFileSync(f, 'utf8'));
    for (const me of methods(f, text)) {
      total++;
      if (!IDENTITY.test(me.params)) continue;
      // 三类噪声，逐条排除，理由就是它们各自会让人怎么误读输出：
      //  1) 生成物 —— DTO 的字段本来就不在"方法体"里被使用（本轮实测 21 条命中全是 record 组件）；
      //  2) 大写字母开头的"方法" —— 那是构造器/record 分量，身份被存进字段而不是被读；
      //  3) 接口方法 —— 没有体，判定无从谈起（实现类会被单独扫到）。
      if (generated || /^[A-Z]/.test(me.name) || me.abstract) {
        skippedNoise++;
        continue;
      }
      const idName = me.params.match(IDENTITY)[2];
      const uses = bodyUses(me.body, idName);
      if (uses === 0) {
        hits.push({ file: path.relative(ROOT, f).replace(/\\/g, '/'), line: me.line, name: me.name, idName });
      }
    }
  }
  if (total < MIN_METHODS) {
    console.error(`[scan] 只解析到 ${total} 个方法（下限 ${MIN_METHODS}）—— 解析器没看见代码，不是没有违规`);
    process.exit(2);
  }
  console.log(`[scan] 主源码 ${files.length} 个文件、解析到 ${total} 个方法（噪声候选已排除 ${skippedNoise} 条：生成物 / 构造器 / 无体接口方法）`);
  console.log(`[scan] 签名收身份参数、体内零使用：${hits.length} 处`);
  for (const h of hits) {
    console.log(`  ${h.file}:${h.line}  ${h.name}()  未使用参数 ${h.idName}`);
  }
  console.log('[scan] 局限：把身份只传给下游方法（体内出现名字）的写法本工具放过；每条候选都要读实现定性。');
}

/** 体内出现次数，排除"另一条同名字符串"以外的全部形式（字符串已在 strip 阶段变成空格）。 */
function bodyUses(body, idName) {
  const re = new RegExp(`\\b${idName}\\b`, 'g');
  const found = body.match(re);
  return found ? found.length : 0;
}

main();
