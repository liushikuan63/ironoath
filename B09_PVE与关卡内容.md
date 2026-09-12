# B09 · PVE 与关卡内容

> 依赖：B05 ｜ 工期：5 天 ｜ 并行：可与 B10 并行

---

## 开工提示词

````text
见 B00 共享上下文 + B01~B05 已完成。现在开始 B09：PVE 与关卡内容。

按 B00 固定流程走第一步、第二步。

本批次一句话交付物：野怪、资源点采集、叛军据点、章节副本四种 PVE + 体力系统 + 扫荡。

关卡设计原则（生成 chapter.csv 时必须遵守）：
- 每 5 关一个"检查点关"，难度跳变，卡一下玩家，制造养成与付费动机
- 每章末 BOSS 关必须有机制而非纯数值（如"每 2 回合召唤援军"），逼玩家换阵型
- 前三章必须零氪可三星通关（首日体验底线）
````

---

## 一、必做清单

### 1. 世界野怪

- 地图散布，等级 1~50，掉落资源 / 加速道具 / 武将碎片
- **每日讨伐次数上限**（防刷），耗尽后 UI 明确提示"明日重置"，绝不静默失败

### 2. 资源点采集

- 派兵占领采集，持续产出
- **可被其他玩家抢夺**（这是 PVP 的重要触发点，也是玩家之间产生摩擦的入口）
- 受负载上限与采集速度约束

### 3. 叛军据点

- 联盟可集结攻打，共享奖励
- **设计意图**：引导玩家进联盟（与 B10 联动）

### 4. 章节副本

- 线性推图，每章 10 关
- **三星条件**：无损 / 限时 / 兵种限制
- 章节宝箱给主线资源

### 5. 体力 / 行动力

- 上限随等级提升，每 X 分钟恢复 1 点
- 可购买（付费点）
- **溢出不累积超上限**

### 6. 扫荡

- 三星后可扫荡
- **扫荡结果必须与普通战斗一致**（走同一个 `simulate()` 函数，只是不播放动画）

---

## 二、关卡设计原则（生成 `chapter.csv` 时严格遵守）

| 原则 | 说明 |
|---|---|
| 检查点关 | 每 5 关一个难度跳变，卡一下玩家 |
| BOSS 机制化 | 每章末 BOSS 有机制而非纯数值，逼玩家换阵型 |
| 零氪可通 | **前三章必须零氪可三星**（首日体验底线） |
| 难度曲线 | `D(n) = D0 × 1.25^(n-1)`（见 B00） |

BOSS 机制示例（配置化，至少实现 3 种）：
```
reinforcement  每 2 回合召唤援军（逼玩家速攻）
shield_phase   血量低于 50% 进入防御姿态（逼玩家换兵种）
counter_strike 受到攻击时反弹伤害（逼玩家带治疗/减伤武将）
```

---

## 三、输入 / 输出契约

```java
// 讨伐野怪
public record AttackMonsterReq(String monsterId, Map<UnitType, Long> units, List<String> heroes) {}
public record AttackMonsterResp(String marchId, long arriveAt) {}   // 走 B07 行军系统

// 章节关卡挑战
public record ChallengeStageReq(String stageId, Map<UnitType, Long> units, List<String> heroes) {}
public record ChallengeStageResp(String battleId, BattleResult result,
                                 int stars, List<RewardItem> rewards) {}   // stars ∈ [0,3]

// 扫荡（最多 10 次，只发 1 次请求）
public record SweepReq(String stageId, int count) {}
public record SweepResp(List<SweepResult> results, long staminaCost) {}
public record SweepResult(int stars, List<RewardItem> rewards) {}

// 采集
public record GatherReq(String resourcePointId, Map<UnitType, Long> units) {}
public record GatherResp(String marchId, long gatherRate, long loadCap) {}

// 体力
public record StaminaResp(long current, long cap, long recoverPerMin, long nextPointAt) {}
```

---

## 四、验收标准

| # | 验收项 | 判定 |
|---|---|---|
| 1 | **战斗失败不扣体力**（或只扣 1 点，减少挫败）—— 明确写出你的选择与理由 | 设计说明 |
| 2 | **扫荡一致性**：同一 stageId + 同一 seed，扫荡结果与手动战斗结果逐字段一致 | JUnit |
| 3 | 每日讨伐次数耗尽后，UI 明确提示"明日重置"而非静默失败 | 手工 |
| 4 | **零氪可通前三章**：用 B02 的零氪 7 天模型模拟，前三章全部三星可达 | 脚本 |
| 5 | BOSS 机制正确生效（援军召唤 / 防御姿态 / 反弹伤害各测一遍） | JUnit |
| 6 | 体力溢出不超上限，恢复时间计算正确 | JUnit |
| 7 | 采集被抢夺时，已采集部分正确结算，不丢失 | JUnit |
| 8 | 叛军据点集结奖励按贡献分配，参与即有奖 | JUnit |
| 9 | 扫荡 10 次只发 1 次请求，结果聚合展示 | 抓包 |
| 10 | 副本三星条件判定正确（无损 / 限时 / 兵种限制） | JUnit |

---

## 五、禁止项

- ❌ 不要让扫荡走独立结算逻辑（必须复用 `simulate()`）
- ❌ 不要让 BOSS 只是数值堆砌（必须有机制）
- ❌ 不要静默失败（次数耗尽、体力不足都要给明确提示）
- ❌ 不要让体力溢出超过上限
- ❌ 不要在客户端计算战斗结果
- ❌ **不要让扫荡走独立结算逻辑**（必须复用 `BattleSimulator.simulate()`）
- ❌ 不要用 `double` 计算体力与掉落（全程 `long`）
- ❌ 不要用定时任务给全体玩家重置体力（用惰性结算，登录时按时间差补）

---

## 六、需要你确认的开放问题

1. **战斗失败是否扣体力**：建议不扣（降低挫败，鼓励尝试）
2. **章节数量**：首发建议 10 章（100 关），后续按版本更新
3. **每日讨伐上限**：建议 20 次（防刷又不至于限制正常游玩）
