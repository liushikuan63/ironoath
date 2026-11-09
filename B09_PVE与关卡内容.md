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


### 2026-10-26现行量具边界（原验收4目标不变）

`--f2p-stages` 的默认6833是#564历史固定参考，未由当前七天模型实时计算；`--troops` 是调用方显式纯步兵输入，`--f2p-days` 仅改变展示标签。不得把累计造兵或旧费用单位换算当作第七天合法可用编队。

本轮纠正原输出“零氪第N天军队/当前资源换算”的错误来源声明；兵力默认值、逐关简化模拟和原退出语义未改。旧量具对unitRestriction筛守方而非执行玩家准入，且未按生产装配BOSS与医院/武将/科技画像、未判累计三星，因此其胜率表及退出0不构成“零氪7天前三章全部三星”证据。该原验收仍未关闭。后续须先冻结合法七天画像，再补生产准入/BOSS/三星同源正反例；不能为满足目标悄改资源、兵种、医院或难度。

`F2pStagesProvenanceTest` 覆盖默认历史来源、显式输入/天数标签分离及标签变化不改变现有诊断表/返回值；它证明来源声明，不代替全三星验收。


### 验收4的量具准入纠正（2026-10-26，P1-B2）

`StageUnitRule` 抽取原生产 `NONE/NO_SIEGE/CAVALRY_ONLY/RANGED_ONLY` 真值表，使用 game-core 既有 UnitKind；真实 StageAppService 与离线量具按枚举名适配同一判据，分层无新增依赖。`--f2p-stages` 现在只校验玩家显式纯步兵输入：不符关卡准入的行输出“准入不满足/未模拟”，不打印伪胜率；守方四兵种不再按玩家限制删减。6833 历史默认、显式 troops、days 标签及“诊断完成不判验收”的返回合同保留。

三条真实反证覆盖当前骑兵关、合成远程限定关，以及合法步兵面对纯攻城守军时旧量具把守军抹空的异常。测试临时修改配置副本，未改生产关卡数值。核心全16组合及配置/协议枚举对应、原关卡API继续回归；结果以该切片交付回执为准。

原“合法零氪七天前三章全三星”仍未验：主树现存 default.keys 实为2026-10-02的45天旧结果，缺当前可部署军队/武将/科技/医院输入；累计造兵不等于当前军队。BOSS、敌方阶级及实际三星累计的同源装配仍是已定义后续量具工作，不能用本片准入闭环代替完整B09-4或真机验收。


### 验收4的 BOSS 同源接线（2026-10-26，P1-B3）

`BossMechanic.from(Type, GlobalParamSource)` 集中映射原五项 BOSS global 参数；真实 BattleRulesAssembler 与 CLI 的 BattleParamsResolver 使用同一纯工厂，未新增参数、默认数值或模块依赖。NONE 不读取机制参数，其他类型只读自身字段，已有验证器继续拒绝缺失/非法参数。每次调用重新装配；已构造的战斗机制保留原值，后续热更不改历史输入。

`--f2p-stages` 现在把关卡声明的机制真正传入 BattleInput，并按实际 RoundSnapshot.skills 的 boss 来源记录累计触发次数；触发次数可为0，声明机制不等于机制已触发。非法玩家编队仍在装配/模拟前拒绝，守方完整性及原历史输入说明保留。合成反证仅在临时配置副本把反射比例设为1.0以避免小兵量整数取整归零；未改生产 global。

本片只关闭机制参数/接线差异。敌方阶级、合法七天可部署军队/武将/科技/医院输入和实际累计三星仍未齐备，原B09-4完整验收保持未通过；原生设备/平台也未以本地CLI替代。
