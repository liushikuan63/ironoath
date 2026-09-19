// 职责：CI 静态检查 —— **public 方法收了 playerId 却在方法体里一次都没用它**，一律判红。
// 症状族：身份是框架按玩家解出来后当第一个参数传下去的，于是"忘了做归属校验"在编译期、
//   类型检查、契约同步、单测（夹具常常只有一个玩家）里全都看不见；运行时表现为
//   甲拿着自己的登录态、把乙的资源 id 填进请求体，就读到或改到了乙的东西 —— 一行日志都不会有。
// 依赖：scripts/lib/java-method-scan.js（与只读宽口径清单 scan-identity-dropped.js 共用同一套解析）。
//
// 判什么：`public`、名字小写开头（构造器与 record 分量不是"方法"）、参数表带身份名、
//   方法体里该身份名出现 0 次。字符串与注释先剥掉，所以"只在日志文案里提 playerId"不算使用。
// 放过什么（两处，都是刻意的）：
//   ① 把身份往下传（体内出现名字）—— 卡口判不了"传过去之后有没有真的用"，那是等价测试的活；
//   ② 带 `identity-exempt: <理由>` 的 —— 与 `mongo-save-exempt:` 同一形状：豁免必须写理由，
//      理由少于 10 个字符直接判红。空口豁免比没豁免更糟。
// 不判什么：private/protected（内部助手常常是签名从别处抄来的死参数，属可读性问题不是越权），
//   生成物（DTO 的字段本来就不在方法体里被读）。

const path = require('path');
const { scan } = require('./lib/java-method-scan.js');

const ROOT = path.resolve(__dirname, '..');
const MIN_FILES = 400;
const MIN_METHODS = 1500;
const MIN_IDENTITY_BEARING = 120;
const MIN_REASON_CHARS = 10;
const GENERATED = /[\\/]generated[\\/]/;

function main() {
  const { files, parsedMethods, identityBearing, findings } = scan(ROOT, { onlyPublic: true });
  if (files.length < MIN_FILES || parsedMethods < MIN_METHODS) {
    console.error(`[check-identity-used] 只读到 ${files.length} 个主源码、解析到 ${parsedMethods} 个方法`
      + `（下限 ${MIN_FILES} / ${MIN_METHODS}）—— 解析器没看见代码，不是没有违规`);
    process.exit(2);
  }
  if (identityBearing < MIN_IDENTITY_BEARING) {
    console.error(`[check-identity-used] 只有 ${identityBearing} 个 public 方法带身份参数`
      + `（下限 ${MIN_IDENTITY_BEARING}）—— 这是解析失效的形状，不能当成"全部干净"`);
    process.exit(2);
  }

  const judged = findings.filter((f) => !GENERATED.test(f.file));
  const offenders = [];
  let exempted = 0;
  for (const f of judged) {
    const rel = path.relative(ROOT, f.file).replace(/\\/g, '/');
    if (!f.reason) {
      offenders.push(`  ${rel}:${f.line}  ${f.name}()  未使用参数 ${f.idName} —— 要么用它，要么写 identity-exempt: 理由`);
      continue;
    }
    if (f.reason.length < MIN_REASON_CHARS) {
      offenders.push(`  ${rel}:${f.line}  ${f.name}()  豁免理由只有「${f.reason}」`
        + `（少于 ${MIN_REASON_CHARS} 字）—— 空口豁免比没豁免更糟`);
      continue;
    }
    exempted += 1;
  }

  console.log(`[check-identity-used] 主源码 ${files.length} 个文件、${parsedMethods} 个方法，`
    + `其中 ${identityBearing} 个 public 方法带身份参数`);
  console.log(`[check-identity-used] 体内零使用：${judged.length} 处，其中带理由豁免 ${exempted} 处`);
  if (offenders.length) {
    console.error('[check-identity-used][FAIL] 身份在这些方法里被静默丢弃：');
    for (const line of offenders) console.error(line);
    console.error('改法：把身份真正用于查询或归属判断（两侧同一条），或写 identity-exempt: 一句说得清的理由。');
    process.exit(1);
  }
  console.log('[check-identity-used] public 方法没有一个把身份丢掉。');
}

main();
