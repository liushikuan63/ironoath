// 职责：把「Java 主源码里有哪些方法、各自的身份参数在体内用没用」这一份解析收在一处。
// 依赖：node 的 fs/path，无第三方库。
//
// 为什么要抽出来：CI 卡口（`check-identity-used.js`，只判 public 且强制要有豁免理由）与
// 人读的宽口径清单（`scan-identity-dropped.js`，连 private 与无体方法一起看）必须用同一套解析，
// 否则会出现"卡口绿、量具说还有三处"这种谁也说不清的状态 —— 与本目录 endpoint-paths.js 同一条理由。
//
// 解析口径（两条都是刻意的，写在这里免得下一轮以为是漏）：
//  ① 只认 `public|protected|private` 显式声明的方法，接口/抽象（无体）另算一类返回；
//  ② 注释与字符串先剥掉但**保持下标与行数不漂移**（用空格替换），所以"只在日志文案里提 playerId"
//     不会被当成使用，而"把 playerId 当参数传给下游"会被算成使用 —— 后者是本工具明确的放过项。

const fs = require('fs');
const path = require('path');

const MODULES = ['game-common', 'game-config', 'game-core', 'game-battle', 'game-web'];
const IDENTITY = /\b(String|long|Long)\s+(playerId|ownerId|targetPlayerId|actorPlayerId)\b/;
const MODIFIERS = /\b(public|protected|private)\b/;
const PUBLIC_ONLY = /(^|[\s{>])(public)\b/;

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
}

/** 五个模块的 main 源码（测试源码刻意不扫：测试里"拿着身份却不用"是正常写法）。 */
function sources(root) {
  const out = [];
  for (const m of MODULES) {
    const dir = path.join(root, 'server', m, 'src', 'main', 'java');
    if (fs.existsSync(dir)) walk(dir, out);
  }
  return out;
}

/** 去注释与字符串，但逐字符等长替换 —— 行号与下标必须仍然对得上原文。 */
function strip(src) {
  const out = src.split('');
  let state = 'code';
  let i = 0;
  while (i < out.length) {
    const c = out[i];
    const n = out[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') { state = 'line'; out[i] = ' '; out[i + 1] = ' '; i += 2; continue; }
      if (c === '/' && n === '*') { state = 'block'; out[i] = ' '; out[i + 1] = ' '; i += 2; continue; }
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
const NOISE_NAMES = /^(if|for|while|switch|catch|return|new|record|class|interface|enum|assert|throw|super|this)$/;

/** 一个文件里所有"看起来是方法声明"的东西：名字、参数表、体、行号、是否无体。 */
function methods(text) {
  const out = [];
  let m;
  SIG.lastIndex = 0;
  while ((m = SIG.exec(text))) {
    const name = m[1];
    if (NOISE_NAMES.test(name)) continue;
    const openParen = text.indexOf('(', m.index + name.length);
    const closeParen = matchPair(text, openParen, '(', ')');
    if (closeParen < 0) continue;
    const params = text.slice(openParen + 1, closeParen);
    let j = closeParen + 1;
    let bodyStart = -1;
    while (j < text.length && j < closeParen + 400) {
      const ch = text[j];
      if (ch === '{') { bodyStart = j; break; }
      if (ch === ';') break;
      if (!/[\s\w$.<>,[\]]/.test(ch)) break;
      j += 1;
    }
    if (bodyStart < 0) continue;
    const head = text.slice(Math.max(0, text.lastIndexOf('\n', m.index) + 1), m.index);
    if (!MODIFIERS.test(head)) continue;
    const bodyEnd = matchPair(text, bodyStart, '{', '}');
    if (bodyEnd < 0) continue;
    out.push({
      name,
      params,
      body: text.slice(bodyStart + 1, bodyEnd),
      line: text.slice(0, m.index).split('\n').length,
      public: PUBLIC_ONLY.test(head),
    });
  }
  return out;
}

/** 体内出现次数（字符串已被 strip 成空格，所以不会把文案里的 playerId 算成使用）。 */
function usesIdentity(body, idName) {
  const found = body.match(new RegExp(`\\b${idName}\\b`, 'g'));
  return found ? found.length : 0;
}

/**
 * 豁免标记只认**签名上方 14 行内**（也就是 javadoc 里）的那一条。
 * 刻意不认"写在方法体内"：注释在解析前已被剥掉（那是"日志文案里提 playerId 不算使用"
 * 同一个必要动作），认体内等于做一条永远匹配不到的规则。
 */
const EXEMPT = /identity-exempt:\s*(\S.*?)\s*(?:\*\/)?\s*$/;

function exemption(rawLines, lineIndex) {
  for (let i = lineIndex - 1; i >= Math.max(0, lineIndex - 15); i--) {
    const hit = (rawLines[i] || '').match(EXEMPT);
    if (hit) return hit[1].trim();
  }
  return null;
}

/**
 * 扫一遍全部主源码，返回判定用的三组数：
 * 扫到多少方法、其中多少个带身份参数、哪些带身份参数却一次都没用。
 */
function scan(root, { onlyPublic = false } = {}) {
  const files = sources(root);
  const findings = [];
  let parsedMethods = 0;
  let identityBearing = 0;
  for (const file of files) {
    const raw = fs.readFileSync(file, 'utf8');
    const text = strip(raw);
    const rawLines = raw.split('\n');
    for (const me of methods(text)) {
      parsedMethods += 1;
      if (!me.params || !IDENTITY.test(me.params)) continue;
      if (/^[A-Z]/.test(me.name)) continue;
      if (onlyPublic && !me.public) continue;
      identityBearing += 1;
      const idName = me.params.match(IDENTITY)[2];
      if (usesIdentity(me.body, idName) > 0) continue;
      const reason = exemption(rawLines, me.line - 1);
      findings.push({
        file,
        line: me.line,
        name: me.name,
        idName,
        public: me.public,
        reason: reason || null,
      });
    }
  }
  return { files, parsedMethods, identityBearing, findings };
}

module.exports = { scan, strip, methods, usesIdentity, sources, IDENTITY, MODULES };
