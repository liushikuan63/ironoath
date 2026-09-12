"""
校准 gacha 表的「每抽基础概率」，使含保底后的综合概率精确等于公示概率。

抽取算法（与 GachaEngine 逐条对应，改这里必须同步改那里）：
  1. 若 ssrCounter+1 >= ssrPity        -> 结果 SSR（保底）
  2. 否则按四档基础概率掷一次：
       SSR -> SSR
       SR  -> SR
       R/N -> 若 srCounter+1 >= srPity 则升为 SR（保底），否则保持
  3. 计数：出 SSR 则两个计数都清零；出 SR 只清 srCounter；R/N 两个都 +1

三个解耦性质决定了求解顺序：
  · SSR 过程与 SR 保底无关（SR 保底只把 R/N 升级为 SR，不会凭空造出 SSR）
    ⇒ SSR 基础概率可用解析式精确求解。
  · 给定 SSR 基础概率后，剩余质量 X = 1 - ssrBase 在 SR/R/N 之间分配；
    SR 的实际频率只取决于 srBase 与 (X - srBase)，与 R/N 之间怎么分无关
    ⇒ SR 基础概率是一维问题，用模拟二分求解。
  · R 与 N 在算法里完全对称（都是「非 SSR 非 SR」，都被 SR 保底同样处理）
    ⇒ 实际频率之比恒等于基础概率之比，按公示比例分摊即可；
    又因为四档实际频率之和必为 1，SSR/SR 已校准，R/N 自动落到公示值。
"""
import io, json, os, collections, random


def ssr_effective(base, pity):
    """解析式：含保底的 SSR 综合概率 = 1 / E[两次 SSR 之间的抽数]。"""
    q = 1.0 - base
    m = pity - 1
    s = (1 - (m + 1) * q ** m + m * q ** (m + 1)) / ((1 - q) ** 2)
    e = base * s + pity * q ** (pity - 1)
    return 1.0 / e


def solve_ssr_base(target, pity):
    lo, hi = 1e-9, min(target, 0.999)
    if ssr_effective(hi, pity) < target:
        raise SystemExit("保底 %d 抽时即使 base=%.6f 也达不到 %.4f" % (pity, hi, target))
    for _ in range(200):
        mid = (lo + hi) / 2
        if ssr_effective(mid, pity) < target:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def simulate(b_ssr, b_sr, b_r, b_n, ssr_pity, sr_pity, n, seed):
    rng = random.Random(seed)
    ssr = sr = r = nn = 0
    c_ssr = c_sr = 0
    for _ in range(n):
        if c_ssr + 1 >= ssr_pity:
            tier = "SSR"
        else:
            u = rng.random()
            if u < b_ssr:
                tier = "SSR"
            elif u < b_ssr + b_sr:
                tier = "SR"
            elif u < b_ssr + b_sr + b_r:
                tier = "R"
            else:
                tier = "N"
            if tier in ("R", "N") and c_sr + 1 >= sr_pity:
                tier = "SR"
        if tier == "SSR":
            ssr += 1; c_ssr = 0; c_sr = 0
        elif tier == "SR":
            sr += 1; c_sr = 0; c_ssr += 1
        elif tier == "R":
            r += 1; c_ssr += 1; c_sr += 1
        else:
            nn += 1; c_ssr += 1; c_sr += 1
    total = ssr + sr + r + nn
    return {"SSR": ssr / total, "SR": sr / total, "R": r / total, "N": nn / total}


def solve_sr_base(target, b_ssr, rest, ssr_pity, sr_pity, n, seed):
    """在 (0, rest) 上二分 sr_base，使 SR 实际频率等于公示值。"""
    lo, hi = 1e-7, rest - 1e-7
    for _ in range(24):
        mid = (lo + hi) / 2
        got = simulate(b_ssr, mid, rest - mid, 0.0, ssr_pity, sr_pity, n, seed)["SR"]
        if got < target:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def q4(x):
    """定点数最多 4 位小数（ConfigValidator.MAX_DECIMAL_SCALE）。"""
    return "%.4f" % x


P = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
               "contract", "config", "gacha.json")
d = json.load(io.open(P, encoding="utf-8"), object_pairs_hook=collections.OrderedDict)

ft = d["fieldTypes"]
if "ssrBaseChance" not in ft:
    new_ft = collections.OrderedDict()
    for k, v in ft.items():
        new_ft[k] = v
        if k == "ssrChance":
            new_ft["ssrBaseChance"] = "DECIMAL_NONNEG"
        if k == "srChance":
            new_ft["srBaseChance"] = "DECIMAL_NONNEG"
        if k == "rChance":
            new_ft["rBaseChance"] = "DECIMAL_NONNEG"
        if k == "nChance":
            new_ft["nBaseChance"] = "DECIMAL_NONNEG"
    d["fieldTypes"] = new_ft

SIM_N = 400_000
CAL_SEED = 20260906
VERIFY_SEED = 777
report = []
new_rows = []
for row in d["rows"]:
    sd = float(row["ssrChance"])
    sr_d = float(row["srChance"])
    rd = float(row["rChance"])
    nd = float(row["nChance"])
    ssr_pity, sr_pity = int(row["ssrPity"]), int(row["srPity"])
    assert abs(sd + sr_d + rd + nd - 1.0) < 1e-9, row["id"]

    b_ssr = solve_ssr_base(sd, ssr_pity)
    rest = 1.0 - b_ssr
    b_sr = solve_sr_base(sr_d, b_ssr, rest, ssr_pity, sr_pity, SIM_N, CAL_SEED)
    rn = rest - b_sr
    b_r = rn * (rd / (rd + nd))
    b_n = rn - b_r

    # 回写成 4 位小数后重新验证：写入的才是玩家真正会经历的数字，必须用它复核
    ws, wr, wk = float(q4(b_ssr)), float(q4(b_sr)), float(q4(b_r))
    wn = float(q4(1.0 - ws - wr - wk))     # 舍入余量全部给 N 档，保证四档之和恰为 1
    got = simulate(ws, wr, wk, wn, ssr_pity, sr_pity, SIM_N, VERIFY_SEED)

    out = collections.OrderedDict()
    for k, v in row.items():
        out[k] = v
        if k == "ssrChance":
            out["ssrBaseChance"] = q4(ws)
        if k == "srChance":
            out["srBaseChance"] = q4(wr)
        if k == "rChance":
            out["rBaseChance"] = q4(wk)
        if k == "nChance":
            out["nBaseChance"] = q4(wn)
    new_rows.append(out)
    report.append((row["id"], sd, got["SSR"], sr_d, got["SR"], rd, got["R"], nd, got["N"],
                   ws, wr, wk, wn))

d["rows"] = new_rows
d["version"] = 2
d["designNote"] = d.get("designNote", "") + (
    "\n\n【v2 增 4 列基础概率：ssrBaseChance / srBaseChance / rBaseChance / nBaseChance】\n"
    "原有的 ssrChance/srChance/rChance/nChance 是**公示概率（综合概率，含保底）**，"
    "新增 4 列是**每抽基础概率**，由脚本校准使「基础概率 + 保底机制」跑出来的实际频率"
    "精确等于公示概率。校准脚本见 tools/gacha-calibrate。\n"
    "为什么必须拆成两组：保底会凭空多出一部分 SSR/SR。标准池公示 SSR 2%、80 抽保底，"
    "若每抽也按 2% 掷，含保底的综合概率会到 2.496% —— 高出公示值 0.496%，"
    "既超出 B06 验收 1 的 0.3% 容差，也构成公示不实（合规红线，B06 §6「不做完不许上线付费」）。\n"
    "求解顺序由三个解耦性质决定：① SSR 过程与 SR 保底无关（SR 保底只把 R/N 升级成 SR，"
    "不会造出 SSR），所以 SSR 基础概率有解析式；② 给定 SSR 后，SR 的实际频率只取决于 "
    "srBase 与 R/N 的总质量，与 R/N 内部怎么分无关，所以是一维二分；"
    "③ R 与 N 在算法里完全对称，实际频率之比恒等于基础概率之比，按公示比例分摊即可，"
    "又因四档实际频率之和必为 1，SSR/SR 校准后 R/N 会自动落到公示值。\n"
    "校准用 40 万次模拟（SR 档标准误约 0.047%，远小于 0.3% 容差），"
    "写入的 4 位小数值再用另一组种子复核一遍 —— 复核用的是玩家真正会经历的那份数字，"
    "不是求解过程中的高精度中间值。舍入余量全部塞给 N 档，保证四档基础概率之和恰为 1。"
)
io.open(P, "w", encoding="utf-8", newline="\n").write(json.dumps(d, ensure_ascii=False, indent=2) + "\n")

print("%-22s %-17s %-17s %-17s %-17s" % ("池", "SSR 公示/实测", "SR 公示/实测", "R 公示/实测", "N 公示/实测"))
worst_all = 0.0
for (pid, sd, gs, sr_d, gr, rd, gk, nd, gn, b1, b2, b3, b4) in report:
    print("%-22s %6.3f%%/%6.3f%% %6.3f%%/%6.3f%% %6.3f%%/%6.3f%% %6.3f%%/%6.3f%%"
          % (pid, sd * 100, gs * 100, sr_d * 100, gr * 100, rd * 100, gk * 100, nd * 100, gn * 100))
    print("%-22s 基础: SSR %.4f SR %.4f R %.4f N %.4f (和=%.4f)"
          % ("", b1, b2, b3, b4, b1 + b2 + b3 + b4))
    worst = max(abs(sd - gs), abs(sr_d - gr), abs(rd - gk), abs(nd - gn))
    worst_all = max(worst_all, worst)
    print("%-22s 最大偏差 %.4f%%（容差 0.3%%）%s"
          % ("", worst * 100, "OK" if worst < 0.003 else "FAIL"))
print("全部池最大偏差 %.4f%%" % (worst_all * 100))
print("gacha.json -> v2")
