#!/usr/bin/env node
/**
 * 职责：只读清单 —— 哪些方法**收了身份参数（playerId / ownerId / …）却在方法体里一次都没用**。
 * 依赖：scripts/lib/java-method-scan.js（与 CI 卡口 `check-identity-used.js` 同一套解析，两份口径不会各说一套）。
 *
 * 与卡口的分工：卡口只判 public 且强制豁免理由，这一份**连 private/protected 一起列**，
 * 因为死参数（签名从别处抄来、其实用不上）属可读性问题，不该把 CI 判红，
 * 但横扫时值得看见 —— 它常常就挂在真正的归属校验旁边。
 *
 * 输出只是**候选**：判不了"传下去之后有没有被用"（体内出现名字即视为使用），
 * 也判不了"用了但用错地方"。每一条都要读实现定性（收口清单 #76 的教训：4 个候选里只有 1 个是真缺口）。
 *
 * 反空转下限：解析到的方法数低于下限直接退 2 —— "一个都没匹配上"与"确实没有违规"必须是两个退出码。
 */
const path = require('path');
const { scan } = require('./lib/java-method-scan.js');

const ROOT = path.resolve(__dirname, '..');
const MIN_METHODS = 1500;
const GENERATED = /[\\/]generated[\\/]/;

function main() {
  const { files, parsedMethods, identityBearing, findings } = scan(ROOT, { onlyPublic: false });
  if (parsedMethods < MIN_METHODS) {
    console.error(`[scan] 只解析到 ${parsedMethods} 个方法（下限 ${MIN_METHODS}）—— 解析器没看见代码，不是没有违规`);
    process.exit(2);
  }
  const real = findings.filter((f) => !GENERATED.test(f.file));
  console.log(`[scan] 主源码 ${files.length} 个文件、解析到 ${parsedMethods} 个方法，带身份参数的 ${identityBearing} 个（本清单不限 public）`);
  console.log(`[scan] 签名收身份参数、体内零使用（含 private，生成物除外）：${real.length} 处`);
  for (const f of real) {
    console.log(`  ${path.relative(ROOT, f.file).replace(/\\/g, '/')}:${f.line}  ${f.name}()`
      + `  未使用参数 ${f.idName}${f.public ? '  [public]' : ''}${f.reason ? `  已豁免：${f.reason}` : ''}`);
  }
  console.log('[scan] 本清单只出候选，每条都要读实现定性；CI 判红的是 check-identity-used.sh（只判 public）。');
}

main();
