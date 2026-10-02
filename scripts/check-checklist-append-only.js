#!/usr/bin/env node
// 职责：检查 收口清单.md「只增不删」—— 用**内容级**核对，而不是增删计数。
//
// 为什么不用 git diff --numstat 的删除数（本会话 #731 实测踩过）：
//   纪律原话是「删除数必须为 0」，但**就地补注**这一合法动作会让 git 把同一行
//   记成「删 1 加 1」，于是一条内容毫无损失的行被判成红线 —— #731 那次 numstat 是 `2 / 1`，
//   而逐行核对的结果是「HEAD 的 1339 行全部能在工作区找到同前缀的行，未找到 = 0」。
//   ⇒ 计数类判据会被「恰好抵消」骗过，而**只增不删**的真实意图是「没有一行不见了」。
//
// 判据：取 HEAD 版本的每一个非空行，要求工作区里存在一个以它**前 N 字符**开头的行。
//   - 取前缀而不是整行相等：就地补注会在行尾追加内容，整行相等会误报。
//   - 取非空行：空行本来就不承载内容，行序调整不该判红。
// 用法：node scripts/check-checklist-append-only.js 收口清单.md
// 退出码：0 全部行都在；1 有行不见了（打印前若干条）。

const { execFileSync } = require("node:child_process");
const fs = require("node:fs");

const file = process.argv[2] || "收口清单.md";
const PREFIX = 60;

const current = fs.readFileSync(file, "utf8").split(/\r?\n/);
// ⚠️ maxBuffer 必须显式放大：收口清单.md 现有 1300+ 行、每行 1~3KB，
// 远超 execFileSync 默认的 1MB —— 2026-10-02 首次跑就撞上 ENOBUFS，
// 而当时把它 catch 成「读不到 ⇒ 跳过 ⇒ exit 0」，等于**把「读失败」当「通过」**，
// 这道门会形同虚设。下面把两类失败分开。
const MAXBUF = 64 * 1024 * 1024;
let head;
try {
  head = execFileSync("git", ["show", "HEAD:" + file],
                      { encoding: "utf8", maxBuffer: MAXBUF }).split(/\r?\n/);
} catch (e) {
  const msg = String(e.stderr || e.message || "");
  if (/does not exist|exists on disk, but not in|bad revision|unknown revision/i.test(msg)) {
    console.log("[check-checklist-append-only] HEAD 里还没有 " + file + "（首次提交）⇒ 跳过");
    process.exit(0);
  }
  console.log("[check-checklist-append-only] 读 HEAD 版 " + file + " 失败 ⇒ 判红" +
              "（不能当成「跳过」：把读失败当通过，这道门就形同虚设）");
  console.log("  " + msg.split(/\r?\n/)[0]);
  process.exit(1);
}

const prefixes = new Set(
  current.filter((l) => l.trim() !== "").map((l) => l.slice(0, PREFIX))
);

const missing = head
  .map((l, i) => ({ l, i }))
  .filter((x) => x.l.trim() !== "")
  .filter((x) => !prefixes.has(x.l.slice(0, PREFIX)));

if (missing.length === 0) {
  console.log("[check-checklist-append-only] HEAD 的 " + head.filter((l) => l.trim() !== "").length +
              " 个非空行全部仍在 " + file + "（前 " + PREFIX + " 字符逐行匹配）⇒ 只增未删 ✓");
  process.exit(0);
}

console.log("[check-checklist-append-only] 有 " + missing.length + " 行在 HEAD 里存在、工作区里不见了 ⇒ " +
            file + " 被删了内容（它是本项目唯一活得过会话的待修载体，只增不删）：");
for (const m of missing.slice(0, 5)) {
  console.log("  L" + (m.i + 1) + ": " + m.l.slice(0, 120));
}
if (missing.length > 5) console.log("  … 还有 " + (missing.length - 5) + " 行");
process.exit(1);
