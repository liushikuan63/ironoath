#!/usr/bin/env node
// 职责：卡口 —— Mongo 存档的 `save` 是**字段白名单**（`$set` 一行一个字段），
//       而 `*Document` 是整份镜像。新加一个字段却忘了补 `$set` 时：
//       **内存实现照样全绿、没有一条用例会红**，只有在真 Mongo 上静默丢档。
// 依据：B25-S2 的自动续训策略丢过一次（`MongoArmyStore.save` 少了 `.set("autoTrain", ...)`），
//       同一族里 `MongoPlayerStore.save` 也少了 `.set("giftPopup", ...)`（礼包每日限购的账本）。
// 例外：字段带 `mongo-save-exempt: 理由` 注释时不判定（理由必写，且会被打印出来）——
//       目前只有「建号之后不再改」的身份字段（deviceId / createdAt）用它。
// 用法：node scripts/check-mongo-set-coverage.js
// 退出码：0 = 每个 Document 字段都被 save 覆盖；1 = 有字段没被写；2 = 前置不满足。
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const DIR = path.join(ROOT, 'server/game-web/src/main/java/com/ironoath/web/store/mongo');

if (!fs.existsSync(DIR)) {
  console.error(`[check-mongo-set-coverage] 前置不满足：目录不存在 ${DIR}`);
  process.exit(2);
}

/** 取 record 的顶层参数列表（按括号配平，避免被嵌套泛型里的逗号骗到）。 */
function topLevelRecordParams(src, recordName) {
  const m = new RegExp(`public record ${recordName}\\(`).exec(src);
  if (!m) return null;
  let i = m.index + m[0].length - 1; // 指向 '('
  let depth = 0;
  let out = '';
  for (; i < src.length; i++) {
    const c = src[i];
    if (c === '(') {
      depth++;
      if (depth === 1) continue; // 列表开括号本身不进内容
    }
    if (c === ')') {
      depth--;
      if (depth === 0) break;
    }
    if (depth >= 1) out += c;
  }
  return out;
}

/** 参数块 → [{name, exempt}]。按顶层逗号切分；`@Id` 字段是查询键，不参与判定。 */
function parseComponents(paramsBlock) {
  const parts = [];
  let depth = 0;
  let cur = '';
  for (const c of paramsBlock) {
    if (c === '<' || c === '(') depth++;
    if (c === '>' || c === ')') depth--;
    if (c === ',' && depth === 0) {
      parts.push(cur);
      cur = '';
    } else {
      cur += c;
    }
  }
  parts.push(cur);

  const out = [];
  for (const raw of parts) {
    const hasId = /@Id\b/.test(raw);
    const exemptMatch = /mongo-save-exempt:\s*([^\n*]+)/.exec(raw);
    const code = raw
      .replace(/\/\*[\s\S]*?\*\//g, ' ')
      .replace(/\/\/[^\n]*/g, ' ');
    const ids = code.match(/[A-Za-z_$][A-Za-z0-9_$]*/g);
    if (!ids || ids.length < 2) continue;
    if (hasId) continue;
    out.push({
      name: ids[ids.length - 1],
      exempt: exemptMatch ? exemptMatch[1].trim() : null,
    });
  }
  return out;
}

const problems = [];
const skipped = [];
const exempted = [];
const checked = [];
const storeFiles = fs.readdirSync(DIR).filter((f) => f.startsWith('Mongo') && f.endsWith('.java'));
const storeSrcCache = new Map();
const storeSrc = (f) => {
  if (!storeSrcCache.has(f)) storeSrcCache.set(f, fs.readFileSync(path.join(DIR, f), 'utf8'));
  return storeSrcCache.get(f);
};

for (const file of fs.readdirSync(DIR).filter((f) => f.endsWith('Document.java')).sort()) {
  const recordName = file.replace('.java', '');
  const src = fs.readFileSync(path.join(DIR, file), 'utf8');
  const params = topLevelRecordParams(src, recordName);
  if (params === null) {
    problems.push(`${file}：找不到 public record ${recordName}( ... )，卡口无法判定`);
    continue;
  }
  const components = parseComponents(params);

  // 哪几个 Store 在写这份文档：文件里出现 `<RecordName>.class`（Mongo 映射必须带 class 字面量）。
  // 命名不逐一对应（SeasonLedgerDocument → MongoSeasonLedger、TrackEventDocument → MongoTrackStore…），
  // 所以按引用找而不是按文件名拼。
  const writers = storeFiles.filter((f) => new RegExp(`\\b${recordName}\\.class`).test(storeSrc(f)));
  if (writers.length === 0) {
    skipped.push(`${file} → 没有 Store 引用它（跨模块或未实现），不判定`);
    continue;
  }
  // 文档里以常量形式给字段起的别名（如 SocialPlayerDocument.FIELD_BLOCKED = "blockedPlayerIds"）：
  // 更新语句里用的是常量名，光扫字面量会误判成「没写」
  const docConstants = new Map();
  for (const m of src.matchAll(/String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g)) {
    docConstants.set(m[1], m[2]);
  }
  const WRITE_OPS = 'set|setOnInsert|inc|push|pull|pullAll|addToSet|max|min|unset|pop';
  const literalWrites = (text) =>
    [...text.matchAll(new RegExp(`\\.(?:${WRITE_OPS})\\(\\s*"([A-Za-z0-9_]+)"`, 'g'))].map((m) => m[1]);
  const constantWrites = (text) => {
    const out = [];
    const re = new RegExp(`\\.(?:${WRITE_OPS})\\(\\s*${recordName}\\.([A-Z][A-Z0-9_]*)\\s*,`, 'g');
    for (const m of text.matchAll(re)) {
      const field = docConstants.get(m[1]);
      if (field) out.push(field);
    }
    return out;
  };

  // 写入清单的判定域（只判**能静态归属**的那一半）：
  //  - 写者必须是「一个 Store 只写这一份文档」的：一个 Store 管好几份文档时（MongoSocialStore 等），
  //    文件里的 $set 属于哪一份文档静态说不清，那份文档就不判定并打印出来（边界写在输出里，不藏在代码里）；
  //  - 文档自己声明的白名单（`toUpdate()` 里那串 .set）一并算数：有的文档把更新清单放在文档类里（AllianceDocument）；
  //  - 更新算子取并集：$set/$setOnInsert/$inc/$push/$addToSet/$pull/$max… 都是"写进去"。
  const docWhitelist = new Set([...literalWrites(src), ...constantWrites(src)]);
  const singleTypeWriters = writers.filter((f) => {
    const referenced = new Set(
      [...storeSrc(f).matchAll(/\b(\w+Document)\.class/g)].map((m) => m[1])
    );
    return referenced.size === 1;
  });
  if (singleTypeWriters.length !== writers.length) {
    skipped.push(
      `${file} → ${writers.join('/')} 同时写多份文档（$set 归属不了），不判定`
    );
    continue;
  }
  const written = new Set(docWhitelist);
  for (const f of singleTypeWriters) {
    for (const w of [...literalWrites(storeSrc(f)), ...constantWrites(storeSrc(f))]) {
      written.add(w);
    }
  }
  if (written.size === 0) {
    skipped.push(`${file} → ${writers.join('/')} 全是整份写入，不判定`);
    continue;
  }
  // 「整份写入」与「镜像式白名单更新」并存时的判别：镜像式白名单（更新时把整份文档逐字段搬过去）
  // 必须覆盖全部字段；只改个别字段的定向更新（已读/领取/计数、乃至邮件里的点路径 mail.readAt）
  // 不在本卡口的判定范围内 —— 它们本来就只该写那一个字段。
  // 判别用覆盖率：写到的字段占文档字段的一半以上，才当成镜像式白名单。
  const MIRROR_RATIO = 0.5;
  const matched = components.filter((c) => written.has(c.name)).length;
  const wholeDocPath = singleTypeWriters.some((f) =>
    new RegExp(`mongo\\.(?:save|insert)\\([^;]*\\b${recordName}\\b`).test(storeSrc(f))
  );
  if (wholeDocPath && matched < components.length * MIRROR_RATIO) {
    skipped.push(
      `${file} → ${writers.join('/')} 有整份写入，白名单只覆盖 ${matched}/${components.length} 个字段（定向更新），不判定`
    );
    continue;
  }

  for (const c of components) {
    if (written.has(c.name) || c.name === 'version') continue;
    if (c.exempt) {
      exempted.push(`${file}.${c.name} —— ${c.exempt}`);
      continue;
    }
    problems.push(
      `${file}：字段 \`${c.name}\` 不在写入清单里（写者 ${writers.join('/')} 与文档自身的 toUpdate） —— ` +
        `内存实现照样绿，Mongo 上每次重读都会丢掉它。` +
        `修法：补 .set("${c.name}", ...)，` +
        `并在对应的 Mongo*ContractTest 里补一条逐字段往返（或加 mongo-save-exempt: 理由 说明它为什么不该被改）。`
    );
  }
  checked.push(`${file}（${components.length} 字段 / ${written.size} 处写入 / 写者 ${writers.join('、')}）`);
}

if (problems.length > 0) {
  console.error('[check-mongo-set-coverage] FAIL —— Mongo 存档会静默丢档：');
  for (const p of problems) console.error(`  - ${p}`);
  console.error(`\n[check-mongo-set-coverage] 已判定 ${checked.length} 份文档，其中 ${problems.length} 处缺写入。`);
  process.exit(1);
}

console.log(`[check-mongo-set-coverage] OK —— ${checked.length} 份文档的每个字段都在 save 的写入清单里`);
for (const s of skipped) console.log(`  跳过：${s}`);
const exemptNote = exempted.length === 0 ? '（无豁免）' : `豁免 ${exempted.length} 项：`;
console.log(`  ${exemptNote}`);
for (const e of exempted) console.log(`    - ${e}`);
