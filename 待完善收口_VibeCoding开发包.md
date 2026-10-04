# 待完善收口 · Vibe Coding 开发包（2026-09-28）

> **来源**：本轮审查（[待完善审查_2026-09-28.md](./待完善审查_2026-09-28.md)）—— 用户「审查一下，现在是否还有待完善内容」，
> 逐条用当轮命令复核后派生。**不是新需求**：这里每一条都是「服务端已就绪 / 规格已在，缺的是消费面或收尾」。
> **体例**：沿用 [SLG对标补充_VibeCoding开发包.md](./SLG对标补充_VibeCoding开发包.md) 的任务卡形态（现状 → 依赖 → 可投喂卡 → 完成定义），
> 与 B17~B25 批次文件的七段结构一致；不新增 Bxx 编号，也不重写任何已交付系统。
> **口径**：卡里的「现状」全部是 2026-09-28 21:2x~21:5x 的现跑读数（`路径:行号` 可复查）。
> 引用前**先重跑那一条命令** —— 本仓最贵的教训是「文档说 A、代码是 B」。

---

## 一、任务装配与完成定义

### 1.1 优先级与推荐顺序

| 顺序 | 任务 | 一次交付什么 | 依赖 | 为什么排这里 |
|---|---|---|---|---|
| ① | **V10 抽卡记录查询上屏** | `/gacha/history` 从「有端点无入口」变成玩家查得到 | 无（服务端与契约都在） | **合规项**：概率公示 + 最近 50 次可查 + 日志 90 天，缺一即公示不实；改动面最小 |
| ② | **V11 装备强化写侧接线** | `/equip/forge` 接上，装备能强化 | 无（读侧面板已在） | 阻塞文案层（`blockReasonText`）早就写好，只差点下去发请求 |
| ③ | **V12 伤兵治疗加速** | `armyTreatSpeedUp` 从「唯一零调用点」变成点得动 | V11 的选择器改法可复用 | 契约那一半已修完，剩「选择器加治疗中一项 + 按 kind 路由」 |
| ④ | **V16 台账漂移修正** | 三处过期台账改准 | 无 | 纯文档、零风险；不修则下个会话照旧数开工 |
| ⑤ | **V14 美术署名页** | credits 页上屏 | 需列全部第三方素材授权 | CC BY 3.0 的**授权条件**，不做不能发布 |
| ⑥ | **V13 国家系统客户端接入** | 分 S1（入籍+国库）/ S2（外交+国策+国家科技） | B13 域与服务端已交付；**国战部分等压测** | 最大的玩法缺口（10 个端点零绑定），要出界面，一格做不完 |
| ⑦ | **V15 聊天历史入口** | — | **待裁决** | 先要口径：「聊天只看最新几条」是有意还是缺陷 |
| ⑧ | **V17 国策投票** | 提案 → 投票 → 生效 → 过期一整条 | **规格草案已出，等 9 条裁决** | B13 §4 唯一的空白格；协议自己写明「先定协议再写实现吃过亏」，所以草案先于代码 |

**顺序纪律**：①~④ 可连续交付，每格独立验证、独立提交；⑤ 要先把授权清单列全；⑥ 内部再分 S1/S2，**不许一轮里开两个 S**；
⑦ 在拿到口径前**不动代码**（本仓禁止替产品决定）；⑧ 裁决未落之前同样**不动代码**，
先做的是把草案 §八 的 9 条一次裁完。

### 1.2 每次必带的总提示词

与 [SLG对标补充_VibeCoding开发包.md](./SLG对标补充_VibeCoding开发包.md) §2.2 同源；每次投喂「本卡 + 该卡点名的 Bxx/Cxx」，
不要把整库文档塞给 AI。

```text
角色：你是熟悉 Cocos Creator 3.8 + TypeScript + Java 17/Spring Boot 的 SLG 主程。
目标：只交付选中的一个 Vxx 任务的一个完整增量；遵守 B00、对应 Bxx 已裁决规格和 C00。
第一步：先查 git status，备份将编辑的现有文件，保留其他人的修改；只读复核任务卡的现状证据（引用前先重跑那条命令）。
第二步：≤20 行说明目标/依赖/拆分，先列成功、拒绝、取消、重试、重登的验收用例。
        把“已实现”“已有但未接 UI”“仅草稿”“待裁决”“未验证”分开。
第三步：先契约与测试，再实现，再 UI 接线；一次不超过 600 行，不能只写没有生产调用点的类。
        新协议只改 contract/proto 真源并运行 npm run gen，不手改生成物。
第四步：同轮执行仓库四类验证（bash scripts/test.sh、bash scripts/check.sh、headless 构建、真启动 + 探针/截图），
        记录本轮构建退出码与进程/端口。只跑单测不能标玩家闭环完成；缺设备或凭据就说明未验证，不伪造通过。
禁止：改赛季长度/王城战时长/经济价格、绕过 AttackGuardService、加免费补偿或 Bot 特权、
      替未裁决规则选默认值、把内部 id/枚举名印给玩家、顺手重构无关文件。
输出：文件清单、实际变更、自测命令及结果、截图位置、未完成项、更新本包的实施记录段。
裁决：新口径给至少三种可回退方案与收益/成本；只有受其影响的实现暂停，其他已明确工作继续。
不自动提交、推送、部署，也不操作生产服务。
```

---

## 二、可直接投喂的功能任务卡

### V10 · 抽卡记录查询上屏（合规）

**现状（2026-09-28 现跑）**：
服务端在：`server/game-web/src/main/java/com/ironoath/web/controller/GachaController.java:92` `@GetMapping("/history")` → `GachaAppService.history(playerId)`。
契约在：`contract/proto/pay.schema.json:436` `GachaHistoryResp{records: List<GachaRecord>, retentionDays, serverNow}`，
生成物注释写明「最近 50 次抽取记录（B15 §三 合规要求）」「下发 `retentionDays` 是为了合规可核」。
客户端**零绑定**：`client/assets/scripts/game/session/GameApi.ts` 只有 `gachaPools()`(L374)、`gachaProbability()`(L379)、`gachaDraw()`(L366)；
`grep gachaHistory` 全客户端零命中。
相关面已在：`game/gacha/GachaPanel.ts`(166 行，面板数据组装)、`scene/GachaDisclosureView.ts`(311 行，概率公示)、`game/gacha/GachaDisclosure.ts`(131 行)。

**依赖**：B15 §三（合规口径）、B06（抽卡）；无需新端点、无需改契约。

```text
任务：让玩家在抽卡面里查得到「最近 50 次抽取记录」，这是 B15 的合规项，不是锦上添花。
必做：V10-a 读侧绑定：GameApi 加 gachaHistory()（只读，走 read，不加幂等键 —— 它不改变任何状态）；
      V10-b 纯逻辑视图模型：把 GachaHistoryResp 转成「每行一句人话」（时间、池子、结果），放在 game/gacha/ 下，
            与 GachaPanel 同层、可单测；池子名与物品名的中文来自服务端下发，客户端不抄表、不拼 id；
      V10-c 入口与承载：在已有抽卡面（导航「招募」那一格的 GachaDisclosureView 同屏或同面板另一页签）里加「抽取记录」，
            含 retentionDays 的展示（「记录保留 N 天」照服务端念）；空记录要说「还没有抽取记录」，不是空白；
      V10-d 探针：tools/ 下新增或复用一份运行时量具，真点击进记录页并读屏上文本。
入口：GameApi（新方法）→ AppRoot（拉取与错误收口，走与其它面板同一个 points/收口点）→ GachaDisclosureView 或同面板页签。
输入输出：请求只有身份头（无 body）；响应 records 按时间倒序（最新的在前），客户端不得重排；
          时间字段一律用服务端给的 serverNow 做基准换算，不引本机时钟。
验收：① 未抽过卡的新号：记录页显示空态文案 + 保留天数，零报错；
      ② 抽一次后重进：记录页首行就是刚抽的那次，逐字段等于服务端响应（用探针读原响应做对照）；
      ③ 负例：/gacha/history 返业务错误码时，页面给一句「拉不到记录」并写埋点，不是假装空记录；
      ④ 埋点口径：与抽卡面板族**一致** —— 打开记录页与翻页都是"一次读"（不花钱、不改存档），
         **不发事件**（同 `openGachaProbability`；两条登记与理由写在 `scripts/check-track-coverage.sh` 的 SKIP 里）。
         `check-track-coverage` / `check-track-dictionary` 必须绿。
禁止：把 poolId / itemId / 概率原始小数印给玩家（本仓已有同族事故：黑话门与「不许印裸 id」那一维）；
      客户端自己截断到 50 条（那是服务端的窗口口径，客户端再截一次会有第二个真相）；
      用本机时钟推算记录时间。
开工输出：先给①②③的失败用例，再写实现。
```

完成定义：`bash scripts/check.sh` 退 0 + 服务端 `mvn -f server/pom.xml test` 退 0 + 真产物探针截图（记录页两张：空态 / 有记录）。

---

### V11 · 装备强化写侧接线

**现状（2026-09-28 现跑）**：
服务端在：`EquipController.java:32` `@RequestMapping("/equip")`，`:42` `@GetMapping("/instances")`，`:49` `@PostMapping("/forge")` → `EquipForgeResp`。
契约在：`contract/proto/equip.schema.json:151` `EquipForgeReq`、`:174` `EquipForgeResp`、`:29` `EquipForgeBlockReason`。
客户端：`GameApi.ts:211` 只绑了读侧 `/equip/instances`；`game/equip/EquipPanel.ts`(180 行) **已经把阻塞原因文案写好了**
（`EquipForgeBlockReason` 导入在 L20、`blockReasonText(reason)` 在 L50）、`scene/EquipPanelView.ts`(221 行) 是承载面板，
`AppRoot.ts:3078` 拉 instances、`:3088` 调 `buildEquipPanel`。⇒ **这是"只差一根线"的形状**。

**依赖**：B20 块②（§五②/§五⑤ 两条裁决）、B06 武将页（装备库入口所在）。

```text
任务：把装备强化从「看得见数值、点不动」接到真的发得出去。
必做：V11-a GameApi.forgeEquip(req)（写口，走 mutate，requestId 由请求层注入）；
      V11-b 装备行的「强化」按钮按 blockReason 亮灰并给原因（文案走已有的 blockReasonText，不新写第二份）；
      V11-c 成功/失败收口：成功后重拉 /equip/instances（不本地 +1），失败保留可重试状态并把服务端原因说出来；
      V11-d 探针：真点击强化一次，读前后两次实例列表的等级/消耗差异。
入口：武将页 → 装备库（EquipPanelView）→ 行内按钮 → AppRoot → GameApi.forgeEquip。
输入输出：requestId 幂等；装备以实例 uid 定位（不是配置 id）；强化消耗与成功率的数字一律来自服务端响应与配置，客户端不算。
验收：① 资源够：点强化 → 请求发出 → 重拉后该实例等级 +1、资源按服务端返回扣减；
      ② 资源不够：按钮灰且**写明缺什么**（blockReason 文案），点了零请求；
      ③ 幂等：同一 requestId 重放只强化一次（服务端已有用例，客户端侧验"双击不重复提交"）；
      ④ 负例：服务端拒绝时页面不显示"已强化"。
禁止：客户端判成功率；本地先扣资源再等服务端（会造出"看着扣了其实失败"）；把 EquipForgeBlockReason 的枚举名印给玩家。
开工输出：先列 ①②③④ 的失败用例，再写实现。
```

完成定义：探针截图（强化前/后两帧）+ `check.sh` 退 0 + 客户端单测新增用例全绿。

---

### V12 · 伤兵治疗加速

**现状（2026-09-28 现跑）**：
`client/assets/scripts/game/session/GameApi.ts:307` `armyTreatSpeedUp(req)` 已定义（`/army/treatSpeedUp`）——
报告器现跑 `node tools/report-client-send-paths.mjs` 判定它是**唯一**一个「生产零调用点且连测试探针都没碰过」的方法。
契约那一半**已修完**：`ArmyTreatSpeedUpReq{requestId, itemId}`（旧版强制 `unitId` 而服务端根本不读，见
[客户端发送口缺口清单.md](./客户端发送口缺口清单.md) 军队族那一行）。
`game/session/Choices.ts:60` 有一段**刻意的注释**解释为什么它不在 `buildSpeedupChoices` 里（服务端要求加速必须带 `itemId`）；
`buildSpeedupChoices` 在 `Choices.ts:161`，`AppRoot.ts:1274` 调用它；
`game/army/ArmyPanel.ts:59` 已有 `treatingText`（`:208` 赋值 `'治疗中'`），`scene/ArmyPanelView.ts:532` 已把它画进状态行。

**依赖**：B25-S2（伤兵与治疗）、`army.json`/`item.json` 的加速令配置；V11 的选择器改法可复用。

```text
任务：让「治疗中」那一行能加速，把 armyTreatSpeedUp 从零调用点变成点得动。
必做：V12-a 选择器加一类目标：buildSpeedupChoices 增加「治疗中」项并带 kind（建筑/训练/治疗），
            回调按 kind 路由到对应端点（/city/speedUp、/army/speedUp、/army/treatSpeedUp）；
      V12-b 道具选择：治疗加速必须带 itemId，选项从玩家背包里可用的加速令来（数量与服务端一致），没有可用道具就不给这颗键；
      V12-c 按钮落点：军队面板治疗行（ArmyPanelView:532 那一行）挂键，不放新页面；
      V12-d 探针：造出"治疗中"状态后真点击，读请求与重拉后的剩余秒数。
入口：军队面板治疗行 → 选择器 → AppRoot → GameApi.armyTreatSpeedUp。
输入输出：请求体只有 {requestId, itemId}（**不要编 unitId**，服务端不读它）；秒数只能来自道具配置，客户端不算。
验收：① 治疗中 + 有加速令：点键 → 请求发出 → 重拉后剩余秒数变小、道具数量 −1；
      ② 治疗中 + 无加速令：不给键或按不可用处理并说明原因，点了零请求；
      ③ 不在治疗中：这一项不在选择器里（负例）；
      ④ Choices.ts 那段"为什么不放这里"的注释要同步改准（否则下一个人会以为它仍是有意不做）。
禁止：为这一个动作引入新的弹层体系；把 itemId 印给玩家；绕过道具扣减在客户端减秒。
开工输出：先列 ①②③ 的失败用例与选择器改动面，再写实现。
```

完成定义：探针截图 + `check.sh` 退 0；`node tools/report-client-send-paths.mjs` 的生产零调用点从 **1 → 0**（这条本身就是判据）。

---

### V16 · 台账漂移修正（纯文档）

**现状（2026-09-28 现跑，三处都撞过）**：

| 文档 | 原话 | 现跑真值 |
|---|---|---|
| [客户端发送口缺口清单.md](./客户端发送口缺口清单.md) §「分组（2026-09-22 现数 11 个）」 | 现数 11 个 | 报告器现数 **1 个**（`armyTreatSpeedUp`）；`rankSnapshot`(`AppRoot.ts:536/2338`)、`scoutReports`(`AppRoot.ts:902`)、`itemOpenBatch`、`staminaView/Buy`、`leaveWorld`、`cityCancel`、`armyCancel/SpeedUp`、`armyCollectTreated` 全已接 |
| [上线检查清单.md](./上线检查清单.md) §三 7 子项 | 「弹窗那一半仍没有调用点：`shouldShow(...)` 在 server 主源码里零调用」 | `server/game-web/src/main/java/com/ironoath/web/pay/GiftPopupService.java:95` 就是生产调用点 |
| [收口清单.md](./收口清单.md) §六 | 「客服 / 退款入口 …… 客户端设置页未做 ⇒ 合规文案里是一条指向不存在页面的死链（优先级最高的一条）」 | `client/assets/scripts/game/settings/SettingsPanel.ts` 已实现（`key: 'audio'\|'support'\|'refund'\|'privacy'`）；服务端 `web/release/SupportConfig.java:29 toEntry()` + `SupportEntryEndpointTest` 在 |

```text
任务：把三处"写着缺口、其实早做完"的台账改准，并留下判据防止再漂。
必做：V16-a 客户端发送口清单：表头数字改成现跑值，已接项移进「已接」段（保留原始理由与日期，不删历史）；
      V16-b 上线检查清单：勾掉该子项并写明承载者（GiftPopupService:95）；
      V16-c 收口清单 §六：按本仓惯例就地补注为已实现（划掉 + 写真值 + 保留原始理由），**不删条目**；
      V16-d 三处各附一句「复跑命令」，让人能自己重验。
入口：三份台账 + tools/report-client-send-paths.mjs。
验收：① 改完 `bash scripts/check-checklist-table.sh` 退 0（收口清单表格形状门）；
      ② `git diff --numstat -- 收口清单.md` 的**删除数为 0**（只增不删是本仓的台账纪律）；
      ③ 逐条现跑复核一遍改后的数字。
禁止：为了让数字好看而删条目；把 §六 的原理由一起删掉（理由本身是现行规则）。
```

---

### V14 · 美术署名页（credits）

**现状（2026-09-28 现跑）**：客户端 `credits|署名` **零命中**（扫 `client/assets/scripts`）。
而 `art-src/ATTRIBUTION.md` 与各素材目录的 LICENSE 写明 **Game-icons 为 CC BY 3.0 —— 署名是授权条件**，
`art-src/素材缺口清单.md` 与 CC §8.9.3 的 A14 也把「署名展示」列为待接项。收口清单 §六 记着「与客服/设置页同批做」，
而设置页（`game/settings/SettingsPanel.ts`）已交付 ⇒ 落点现成。

```text
任务：补上第三方素材的署名展示，让 CC BY 3.0 的授权条件成立。
必做：V14-a 列出全部**真正进包**的第三方素材与授权（以 art-src/ATTRIBUTION.md 与各 LICENSE 为准，逐条核对，
            不凭印象；只列许可要求署名的，CC0 不必列或集中注明）；
      V14-b 署名内容做成纯数据（一份可单测的清单），进设置页一级或「关于」，玩家可读；
      V14-c 探针：真进设置页读屏上文本，逐条与清单一致。
输入输出：署名文案（素材名 / 作者或来源 / 许可名 / 许可链接）一律照 LICENSE 原文，不改写、不缩写。
验收：① 探针读到的条目数 == 清单条目数（正反都判：少一条红、多一条也红）；
      ② 屏上不出现内部路径与文件名（同「不许印裸 id」那一族）。
禁止：把 CC0 素材也硬塞进署名页造成噪声；写"图片来源于网络"这类无出处表述；
      为了署名页扩大首包（可用纯文字页）。
```

---

### V13 · 国家系统客户端接入（**S1 + S2 已落地，三项裁决后另有两次补口**）

**现状（2026-09-28 现跑，留档；2026-09-30 已不是这样）**：10 个 `/nation/*` 端点在客户端**零绑定**
（`/nation/found`、`/join`、`/leave`、`/disband`、`/appoint`、`/diplomacy`、`/treasury`、`/treasury/spend`、`/tech`、`/tech/research`；
`sweep-client-endpoints.py` 现跑列为 (a) 类候选，逐条 grep 客户端确认无绑定）。
客户端现有与国家有关的只有三处：排行「国家榜」页签（`game/power/RankBoard.ts:42`）、集结面板的 `case 'NATION'`、
社交摘要那句 `国家 ${nationId}`（`game/social/SocialPanel.ts:148`）。
服务端域与服务都在（B13 已交付）；**但国战那一半是空的**：`WarScoreBoard` 在服务端主源码命中 4 处、**全在类自身**
（`core/nation/WarScoreBoard.java` 的 `class`/构造器/`restore`）⇒ game-web 零消费方。

**结论（2026-09-30 现跑）**：`NationController` 的 **11 个端点全部有客户端绑定**
（`found`/`join`/`leave`/`disband`/`appoint`/`diplomacy`/`GET view`/`treasury`/`treasury/spend`/`tech`/`tech/research`），
`node tools/report-client-send-paths.mjs` 现跑 **128 方法 / 生产零调用点 0**。
另有两处**不在原任务卡里、由后续裁决带出的补口**（都已完成）：
① `GET /social/summary` 的 `nationId` 与 `GET /social/permissions?scope=NATION` 此前是
"B13 接入前恒为 null / 恒回 NONE"的占位，已按真实国籍与官职作答；
② 国家面板的灰键已改由**权限位**（`WITHDRAW_TREASURY` / `APPOINT_OFFICE` / `MANAGE_DIPLOMACY`）
裁决，不再拿 `myOffice` 当代理。
**仍未做的仍是那两件**：**国策投票**（§4 的端点与 DTO 都没有 —— 协议注释写明"先定协议再写实现吃过亏"，
所以它必须与 web 层一起加，不是"漏接"）；**国战整条**（前置是压测，B13 禁止项，不在 S1/S2 范围）。

```text
任务（S1）：国家的最小闭环 —— 入籍（创建/加入/退出/解散）+ 国库（看余额、官员花钱）。S2 另开一轮做外交/国策/国家科技。
必做 S1：V13-a GameApi 绑定 S1 的端点（写口走 mutate + requestId 幂等）；
         V13-b 国家面板（新面）：一句话说清"我现在有没有国家、能做什么"，成员与官员名单**不印裸 id**；
         V13-c 国库：余额与流水，支出必须带 reason 与 payee（域层签名已强制），界面要把这两样显示出来，
                 不允许出现"花了但看不出花给谁"的条目；
         V13-d 权限：能做什么由服务端权限位决定，客户端不写死"只有盟主能"；
         V13-e 探针：建号 → 建国 → 捐/花 → 读屏与读响应对照。
输入输出：国家身份走既有身份头；国库金额一律最小单位整数，格式化只在展示层。
验收：① 无国家：面板给「创建」与「可加入列表」两条路（与社交面板同族做法）——**已达成**，
        可加入列表取自国家榜（服务端没有 `/nation/list`，那是"没有这个端点"而不是"没接"）；
      ② 建国成功：面板显示国家名/成员数/国库余额，逐字段等于服务端 ——**已达成**（`verify-nation-live.mjs` 真链路 52/0）；
      ③ 无权限的玩家：那颗键灰且写明缺哪个权限，点了零请求 ——**已达成**（按权限位，见上面补口②）；
      ④ 负例：服务端拒绝（重名/资源不足/已在国家）时页面说原因，不假装成功 ——**已达成**；
      ⑤ 补充：S2 的验收（科技/外交/任命）见 §四「V13-S2」那两行。
禁止：把国战塞进 S1（它等压测）；客户端自己算国库收支；把成员 playerId 印给玩家。**三条一条都没违反**。
```

---

### V17 · 国策投票（**B13 §4 · 规格草案已出，待裁决后才准开工**）

**状态（2026-09-30）**：一行代码都没有，也**不应该有**。
国策的协议、领域状态、端点、配置表、乘区全部为零（现跑证据见草案 §一），
而 `contract/proto/nation.schema.json:5` 自己写着「先定协议再写实现的做法在本项目吃过亏」。

**规格草案**：[B13S4_国策规格草案.md](./B13S4_国策规格草案.md) ——
含已定口径 10 条（逐条 `文件:行`）、🔴 缺出处 7 条（提案权 / 投票权 / 通过门槛 / 弃权票 / 节奏 / Bot 投票 / 公示粒度）、
B21 §五④ 已裁的 4 条 buff 候选表、乘区归属三方案、以及一份 6.5 天的落地清单。

**开工前置（一条都不能跳）**：
① 草案 §八 的 9 个待裁决点全部有裁决（用 `ask_user_question` 一次给全，收到后逐条写回草案 §三）；
② `SET_NATIONAL_POLICY` 的权限口径与提案权对齐（现跑：`contract/config/role_permission.json:169-176` 是「仅国主」，
   而 `B13:47` 写内政官可提案、`B21:73` 写「国王/内政官」—— 三份来源三个答案）；
③ 乘区 G 的归属定下来（`AttackMultipliers.compose()` 只有一个地方能同时决定国策 buff 与 `#19` 集结加成，**两笔必须合并成一格做**）。

**验证入口**：`tools/verify-nation-live.mjs` 加「提案 → 投票 → 结算 → 生效读回 → 过期」一段 + 两条负例；
`scripts/check-config-consumers.js:122` 的 `NATION_VOTE_DURATION_HOURS` 零引用登记在接出读取方后必须删除
（`bash scripts/check.sh` 退 0 是判据之一）。

**禁止**：加任何常驻定时器（轮次一律惰性驱动，`check-no-scheduled.sh` 是门禁）；
把国策 buff 折进既有乘区（`B21:76` 明写「不许污染既有乘区」）；
在缺出处处自行编数值（本仓禁止替产品决定，编出来的数字会让下一个会话以为它有出处）。

---

### V15 · 聊天历史入口（**已落地：裁决 ③ 真分页**）

**现状（2026-09-28 现跑，已过时但留档）**：`/chat/list` 已绑定（`GameApi.ts:909`），但面板先
`drafts.slice(-CHAT_VISIBLE_ROWS)`（5 条）再交给 `drawRows`，且响应里的 `hasMore` 客户端零读取
（`grep -rn "hasMore" client/assets/scripts/scene/*.ts` 只命中 `GameBootstrap.ts:1414` 一句注释）
⇒ **历史消息在面板里没有入口**。代码注释写着「聊天要看的永远是最新的几条」，看着像有意口径。

**要的口径**（三选一）：① 维持现状 = 设计如此；② 加「查看更多」进入历史；③ 照其它面板改成真分页。

**2026-09-30 裁决：选 ③，并已落地。** 落地要点（下一格别重新想）：

- **页码从最新往回数**（0 = 最新那一页）—— 与其它页签的正序分页**刻意相反**：
  打开、发完一条、换频道/换会话都必须落在第 0 页。沿用正序会让"自己刚发的消息不出现"
  （原 `slice(-5)` 就是在补这个坑）。**这一条踩过一次**：表现层先按"更早 = page-1"传，
  于是页码被夹回 0、点了没反应（探针 33 条里红 3 条）。现在 `turnChatPage(step)` 明写
  **`step=+1` 是往更早**，表现层按页码增量传、不在两处各转一次语义。
- **一屏几条与一次要几条是两个数**：`CHAT_PAGE_ROWS = 5` 是**容量**，真正每页几条走
  `contentPerPage(总数, 5)`（装不下时为翻页行让出一格 ⇒ 通常 4 条）；`CHAT_FETCH_OLDER = 20`
  是"往前翻到底一次向服务端补多少"（= 4 屏，翻 4 次才发一枪）。
- **`hasMore` 从"下发但没人读"变成真在用**：翻到本地最旧那一页且 `hasMore` 为真时，
  「更早」继续亮；点它才带着 `beforeMessageId`（手里最旧那条）去拉下一段并并进本地缓存。
- **翻页键与其它页签的 `pagePrev/pageNext` 刻意不共用**：那两个只改视图自己的 `page` 就够，
  而聊天往前翻到底要发一次请求 ⇒ 页码住在编排层（`AppRoot.chatPage` / `chatHasMoreOlder`）。
- **发完一条回到第 0 页**：停在"更早"那一页时自己刚发的话落在最新页上，屏上什么都不动。

**V15 踩到的两个坑（都是"探针全绿而屏上是错的"那一族）**：

1. **5 条消息 + 1 行翻页 = 6 行塞不下**，翻页行压在输入行上（截图抓到，当时探针 33 条全绿）。
   修法：容量 5 走 `contentPerPage` 为翻页行让位 ⇒ 有翻页行时每页 4 条。翻页行的文案里
   因此多报一个「每页 N 条」——那个数会被"让位"改掉，不说清就没法解释"为什么少了一条"。
2. **判据查错作用域**：占位文案那条一度用 `rows()`（只看 `SocialRow`）去查输入框，
   断言必红而屏上其实是对的。改成查**整个面板**的标签（`textsUnder('social')`），
   顺带加了一条"面板上没有任何标签是引擎默认串 `label`"（同族见 `NationPanelView`）。

---

## 三、不在本包范围内的项（说明理由，避免被当漏做）

| 项 | 为什么不在本包 |
|---|---|
| 国战（王城战/计分/结算） | **前置是压测**（B13 禁止项），压测驱动本身要先解决；属另一条线 |
| 防守集结（哀兵 +10%） | 触发条件无规格 ⇒ 要产品先裁"防守集结是不是一个玩法" |
| 联盟科技另外六个属性 | 六个都是平衡决策（相加/相乘/替换），接错只体现在胜率上，不报错 |
| **联盟领地建筑（堡垒/旗帜）** | **只有计数器，没有玩法**。2026-09-30 现跑：`Alliance.buildTerritory()` **零调用方**（全仓只命中它自己的声明），它**不收坐标**、只 `territoryCount++`；`WorldEntityType.BUILDING` 服务端**从来没有生产者**（`WorldAppService` 里只出现在 `case EMPTY, CITY, BUILDING -> null`）；`WorldEntity.allianceTag` 契约里那句"B10 落地前恒为 null"因此**仍然成立**。客户端那一侧倒是有完整的显示路径（`game/world/WorldLabels.ts:33` 的 `entity.allianceTag ?? level`），但服务端不发 ⇒ 这一维在地图上永不出现。**不是漏接，是这个玩法没做**：要做先得裁"在哪建、花什么、给什么加成"，属产品决策 ⇒ 不替它发明（同时也不把这四个占位当缺陷删掉：删了协议就得改，而契约注释已经把"预留"写清了） |
| 国策投票 | §4 的端点与 DTO 都没有。协议注释自己写明"先定协议再写实现吃过亏"（字段名会反过来限制实现）⇒ 它必须与 web 层一起加，不是"漏接"；`NATION_VOTE_DURATION_HOURS` 仍在 `check-config-consumers` 的零引用例外表里（出处 #47） |
| 未成年人限额 / 实名 | 缺「这个账号是否未成年」这一个输入（外部数据源） |
| 米大师真渠道 / 真机 / 版号备案 | 全是要外部材料或凭据的格子 |
| 30 余份运行时探针的全量复跑 | 要产物 + 活后端，属验证批次，见审查报告 §六 |

---

## 四、实施记录（每完成一格就追加，不攒到最后）

| 格 | 提交 | 验证读数 | 截图/证据 | 未做 |
|---|---|---|---|---|
| **V10** | 未提交（改动在工作树） | `check.sh` **退 0**（含客户端 **964/964**，新增 6 条）；`check-client-typecheck.sh` 退 0（两个 project）；`build-webmobile.sh` 退 0（`missing or invalid = 0`）；探针 `tools/verify-gacha-history.mjs` **退 0**（`PROBE_EXIT=0`、`GH_DRAW=1` 那趟也退 0） | `tmp/gacha-history/closed.png`、`empty.png`、`after-draw.png`（第三张实测屏上：`卫无咎 ｜ 新手招募池 · 刚刚 ｜ 共 1 条`） | 未跑横扫/其它量具（与本格无关，但它们量的是同一份产物，回归留给 §四 的全批复跑）；**未提交、未推送** |
| **V11** | 未提交（改动在工作树） | `check.sh` **退 0**；`check-client-typecheck.sh` 退 0；`verify-equip-runtime.mjs` **退 0（19 通过 / 0 失败）** —— 新增 6 条：两件都有强化键、能强化的点得动、**真发出 `POST /equip/forge`**、请求体 `equipUid` 是实例 uid、带 `requestId`、**灰键零请求（对照组）** | `client/build/equip-verify/equip-panel.png`（金键 / 灰键两态） | 真后端上**没有装备实例可拿**（`verify-b20.sh:182` 记着"全仓库没有一张表发放装备"，#165 ⑥）⇒ 正向链路用 route 夹具（`EQUIP_STUB=1`）注入，真后端那趟只验到空态；未提交 |
| **V12** | 未提交（改动在工作树） | `check.sh` **退 0**（埋点门 74 个动作全有事件）；客户端 **965/965**（新增 1 条：治疗加速只列训练令）；`tools/verify-treat-speedup.mjs` **退 0（14 通过 / 0 失败）** | `tmp/treat-speedup/{treating,picker,idle}.png` —— 选择器里只有「一小时训练令」两档，建造令/研究令不出现 | dev 无"治疗中 + 有训练令"的状态 ⇒ 用 `/army/list` + `/bag/list` 夹具注入；真后端那趟只能验对照组（不在治疗 → 键不可见）；未提交 |
| **V16** | 未提交（改动在工作树） | `check-checklist-table.sh` 退 0；`git diff --numstat -- 收口清单.md` = **4 增 0 删**（只增不删的台账纪律）；三处都附了复跑命令 | — | 三份台账的**其它**过期数字没扫（只改本轮现跑撞出来的三条） |
| **V14** | 未提交（改动在工作树） | `check.sh` **退 0**；`check-client-typecheck.sh` 退 0；客户端 **972/972**（新增 `Credits` 6 条 + `SettingsPanel` 1 条，另同步两条把入口写死成 4 个的旧断言）；`tools/verify-credits.mjs` **退 0（15 通过 / 0 失败）** | `tmp/credits/credits.png`（三组署名 + 许可 + 关闭键，无一行顶出卡片） | 只列了**真正进包**的第三方素材（Game-icons / Kenney / 自生成）；Noto CJK 尚未进包所以没列（`ATTRIBUTION.md` 写着"来源已确认，尚未复制字体文件"）；未提交 |
| V13-S1 | 见 §四 末尾那行 | `check.sh` **退 0**（31 道静态门 + 埋点门 78 个动作全有事件 + 客户端 **988/988**）；`check-client-typecheck.sh` 退 0（两个 project）；`build-webmobile.sh` 退 0（`missing or invalid = 0`）；`tools/verify-nation.mjs` **退 0（68 通过 / 0 失败，5 场）** | `tmp/nation/{00-control-no-alliance,01-no-nation,02-member,02b-spend-form,03-no-office,04-king}.png` | **正向链路（建国/入籍/退国/解散/支出真正发出去并回填）未验**：dev 新号没有联盟 ⇒ 建国三条前置（主城 16 级 / 开服 D14 / 在联盟中）全不满足，建国之后的世界状态更无从造起。正向只能等一个真号；`02b` 那张是**表单开着的形态**，确认键是灰的（用途没填），**没有点下去过**。S2（外交/国策/国家科技）与国战整条仍未动 |
| **V13-S2** | `320f5c0` | `check.sh` **退 0**（31 道静态门 + 埋点门 81 个动作全有事件 + 客户端 **996/996**）；`check-client-typecheck.sh` 退 0（两个 project）；`build-webmobile.sh` 退 0；`tools/verify-nation-s2.mjs` **退 0（89 通过 / 0 失败，9 场）**；`tools/verify-nation.mjs` 复跑 **退 0（68/0）** —— S2 改了同一个面板，S1 那份是回归面 | `tmp/nation-s2/{00-control,01-tech,02-tech-treasuryLow,02-tech-officerLimit,02-tech-notOfficer,02-tech-maxLevel,03-research,04-diplomacy-empty,05-diplomacy-set,06-appoint,07-appointed}.png` | **正向三笔在夹具上跑通了**（研究 / 改关系 / 任命：真发出、带 `requestId`、回执回填）；真后端正向当时仍未验（见下一行，已解决） |
| **V13 真链路** | `320f5c0` 之后的提交 | `tools/verify-nation-live.mjs` **退 0（52 通过 / 0 失败，连跑两次都 0）** —— 全程真 HTTP、零夹具：建号 → 建联盟 → **建国** → 读回逐字段一致 → **国库支出** → 流水四要素 → **国家科技研究**（等级 +1、扣款 = 表里给的下一级花费、重读仍是新等级）→ 二号申请入盟 → 批准 → **任命** → 被任命者自查 `myOffice` → 第二个国家 → **外交 HOSTILE** → 负例（与自己建交被拒 1001）→ **退国** → 再读回 13000 → 清理。`NATION_LIVE_UI=1` 再加一趟**回读屏**（真后端 + 真产物）：屏上国名/等级/成员/国库/周税流水逐项等于服务端 | `tmp/nation-live/live-panel.png`（真数据：`回读屏国44914`｜`成员联盟：1 / 200`｜`国库：10,000 / 500,000`｜`刚刚 · 系统 · 国库周税（第 202640 周 × 1 个成员联盟）`） | 服务端与客户端各加了改动（见下）⇒ 各跑一遍全量：`mvn test` **退 0（1977 项）**、`check.sh` 退 0（**997/997**）。**仍未做**：国策投票没端点；国战等压测 |
| **V15** | 本次提交 | `check.sh` **退 0**（31 道静态门 + 埋点门 81 个动作全有事件 + 客户端 **1004/1004**，新增 8 条分页用例）；`check-client-typecheck.sh` 退 0（两个 project）；`build-webmobile.sh` 退 0；`tools/verify-chat-paging.mjs` **退 0（41 通过 / 0 失败，两场）**；**既有** `tools/verify-chat-runtime.mjs` 复跑 **全部通过**（改了聊天页签的绘制路径 ⇒ 收发/推送/举报/拉黑/关注都是回归面）；`mvn test` 退 0（**1977 项**，服务端本格零改动） | `tmp/chat-paging/{01-newest-page,02-older-page,03-cursor-append,04-oldest-page}.png` | 服务端零改动（`/chat/list` 本来就是游标分页 `beforeMessageId`+`limit`+`hasMore`）。**未做**：跨频道统一页码（现在是"每个频道/会话各自一页"，换频道回到最新——按裁决时写的"全频道统一一页"落地，但"统一"指的是**同一套分页口径**而不是共享页码）；长消息的多行气泡仍是编辑器资产那一批 |
| **CI 探针门** | `bbafa33` | **CI run `36710773516` 结论 success**（`verify` job 4m17s + `probes` job 1m05s，两 job 全绿）。`probes` job 日志：后端**第 21 次探测就绪（约 42 秒）**，`verify-nation-live.mjs` **44 通过 / 0 失败** | 该 job 的定义就在 `.github/workflows/ci.yml`（`probes`，`needs: verify`）；范围诚实说明写在 workflow 注释里 | **只覆盖"不需要前端产物"的那一类**（35 份 `verify-*-runtime.mjs` 里绝大多数要 `client/build/web-mobile`，而 Cocos 构建在 ubuntu runner 上做不到：需登录与许可）。要把它升级成"批跑进 CI"需要一台能跑 Cocos 的自建 runner（系统级改动，需先确认）。**读数差异说明**：同一份探针在本地出现过 44 与 52 两个读数，两次都是 0 失败。探针里有条件块（例：`tools/verify-nation-live.mjs:195` 的国家科技研究那一段被 `researchable !== null` 守着，`NATION_LIVE_UI=1` 另加一段浏览器回读），**但 8 条之差具体落在哪个块，本轮未查明**；门禁按退出码判定，44 与 52 都不影响结论，CI 恒为 44 |
| **V17-A 乘区** | 本次提交 | `mvn -f server/pom.xml -pl game-battle -am test` **退 0（47 项，其中 OrgBonusZoneTest 10 项）**；`mvn -f server/pom.xml test` **退 0**；`check.sh` **退 0**（31 道静态门 + 客户端 **1006/1006**） | `OrgBonusZoneTest`（10 条，每条都配了「能失败的另一半」：正向断言 + 并进旧池的反证） | **本格没有生产方** —— 国策表（B 格）、协议（C 格）、装配（F 格）都还没做，所以线上 `OrgBonus` 恒为 `none()`。城墙的**幅度已定 +10%**（收口清单 #484，`WALL_DEFENSE_BONUS_FIXED`，`balance-sim --wall` 量出来的），**爬升形状仍无出处**且「城墙等级」在存档里是哪个字段还没查清，所以乘区 H 仍无生产方 |
| **#19 集结装配（收尾）** | `2ab512d` | `mvn test` **退 0**；`check.sh` **退 0**（客户端 1018/1018）。`RallyDepartureTest` 12 条全绿、`ConfigTablesAcceptanceTest` 新增 2 条、`OrgBonusZoneTest` 新增 4 条。**`RALLY_ATTACK_BONUS_FIXED` 的零引用例外已删除** —— `check-config-consumers` 现跑「42 张表全部在读、零引用只剩 3 个」 | `PlayerCityBattleService.rallyBonus`（判据 `march.isRallyMarch()`，**不重比人数**：`Rally.depart()` 已在人数不足时拒绝出发）+ `OrgBonus.plus`（国策与集结**相加**而不是二选一） | **+10% 这个幅度没有行为级单测**：一条 1000 vs 1000 的仗，+10% 改不了胜负（那是工具要跑 600 局/点的原因）。装配"有没有发生"由两条机制证：4 条 `plus()` 单测钉合并语义（含覆盖 bug），`check-config-consumers` 钉生产调用点 |
| **dev 时间加速档（裁决采纳）** | `f4b1238` | `mvn test` **退 0**（新增 `DevClockSpeedTest` 3 条）；`check.sh` **退 0**（客户端 1018/1018）；真机探针**退 0（35 通过 / 0 失败）** | `ClockSource` 端口 + `DevClockSpeed`（`@Profile("dev")`，环境变量 `IRONOATH_DEV_TIME_SPEED`，默认 1）��`GameBeansConfig.timeService` 改走该端口。算式是**起点 + 经过的真实时间 × 倍速**，不是「真实时刻 × 倍速」——后者会让重启后时间跳到 1970 年之后 | **加速档期间真机会弹出「自上次登录以来」的离线产出弹窗**（36 秒真实时间就攒出 2 小时 13 分产出），探针没能关掉它 ⇒ **投票段/生效段那两张截图被遮住，只有文本级判据**。关闭方式是按节点名找 `离线汇总知道了`，两版都找不到（先按文案、再按 `Canvas` 下的直接子节点），已改成整棵树扫但**仍未验证成功** |
| **B13 收官（验收矩阵 + 完整全绿）** | 本次提交 | `mvn test` **退 0**；`check.sh` **退 0**（32 道静态门 + 客户端 **1018/1018**）；headless 构建 **退 0**；`verify-nation-policy-ui.mjs` **退 0（41/0）**；`verify-nation-live.mjs` **退 0（71/0）**。验收矩阵现数（node 按状态列码点现跑）：**267 条 = 228 ✅ / 15 🟡 / 24 ⬜** | `验收矩阵.md` #十四 B13 #11 由 ⬜「未实现」翻 ✅ —— **那一格是假陈述**（文档说未实现、代码早已实现），本仓头号缺陷形状 | 24 条 ⬜ 的大头仍是 B13 国战整块（国战积分 / 压测报告 / 全服目标）与 B19、B20 块②。城墙（乘区 H）不在验收矩阵内（不是 B13 的验收项），证据在收口清单 #484/#486 |
| **投票段 + 生效段真机跑通** | 本次提交 | 真机探针 **退 0（41 通过 / 0 失败）** —— 三段全部在真产物上跑到：提案段 → 等投票窗真的开（2400 倍速下约 36 秒**真实等待**，不是拨钟）→ 投票段（投票中 · 生效槽位 1、票数 0·0、**投票段不再显示「现在不是投票时间」**、点一票后票数变 1 = 服务端账本不是本地 +1、赞成名单不再是「还没有人投票」、**同一颗键再点是 greyed**）→ 等投票窗关闭 → 生效段（国策生效中 · 生效槽位、**生效那条带自己的到期倒计时**、公示已清空、提案键回来了） | `tools/verify-nation-policy-ui.mjs` | **三张截图现已完整**（弹窗遮挡已解决，收口清单 #483）：探针按节点名 `离线汇总知道了` 取键、`emit('touch-start', {type, target})` **带事件对象**（不传参时处理函数读 `_event.type` 会抛且被吞掉），仍���在则 `destroy()` 那个纯表现层 overlay |
| **V17-H 补：投票段 / 生效段端点级证据** | `5b7b522` | `NationPolicyEndpointTest` **14 条全绿**；`mvn test` **退 0**；`check.sh` **退 0** | 用 `@TestConfiguration` 覆盖 `TimeService`（投票窗 24 小时拨钟进去）。投票段能投且票数与两份名单一次给全、**同一票不能投第二次（13017）**、Bot 不能投票（**先注册进 `BotRegistry`**）、过窗即结算且生效那条**真被战斗装配读到**、到期自动开下一轮且旧加成归零 | 端点层与真机层的分工：真机层不拨钟（等真的窗口开），端点层拨钟（一次跑完 48 小时两段） |
| **协议补 `activeUntil`（32 → 33 def）** | 本次提交 | `npm run gen` **退 0**；双端编译通过；`check.sh` **退 0**（客户端 1018/1018） | `NationPolicyActiveView` | 协议里原先那句「这个数组天然表达『还剩多久』」**是假的** —— `active` 的元素是 `NationPolicyView`，没有到期时刻。**没有只改注释，而是把那一列补上**：生效中的倒计时由每行自己回答；客户端用例钉住「两条到期时刻不同时各自算各自的」，用整轮一个时刻去减所有行会让第二条显示错 |
| **#19 集结曲线（裁决 A10：先出模拟再定）** | `78160d8` | `balance-sim --rally` 跑出曲线：`check.sh` **退 0**、`mvn test` **退 0**、CLI **退 0**（600 局/点 × 22 个人数比 × 4 档加成 = 52800 局）。**这是测量，不是发明** | `BalanceCli.printRallyCurve` + `BattleParamsResolver.bareArmy(..., attackBonusFixed)`（走**已交付的 `OrgBonus` 乘区 G**，不在工具里另造加成位） | 幅度已定 **+10%**（定点 1000）→ `global.json` 的 `RALLY_ATTACK_BONUS_FIXED`，收口清单 #479。曲线只测了 T1 / 四兵种等量 / PLAIN / PVP_SOLO，换条件要重跑。**分档曲线（人越多加成越高）无出处，不编** |
| **V17-G 视觉半** | `908f8cc` | `check.sh` **退 0**（客户端 **1018/1018**）；`mvn test` **退 0**；headless 构建**退 0**（`missing or invalid = 0`）；`tools/verify-nation-policy-ui.mjs` **退 0（25 通过 / 0 失败，真后端 + 真产物 + 无头浏览器，零夹具）** | `NationPanelView` 第 5 个页签 `drawPolicy`、`GameBootstrap` 两个回调绑定、`AppRoot` 两个写口、`TrackEvents` 3 条（`nation_policy_propose` / `nation_policy_vote`，已进 `数据看板需求.md` §七）、`ElapsedText.remainingText`、`tools/verify-nation-policy-ui.mjs` | **结算/投票段/生效段仍无真机证据**：投票窗 24 小时、真链路不可能等；`drawPolicy` 的投票段与生效段只由视图模型用例覆盖。截图只证提案段 |
| **V17-G 逻辑半** | `26bd2f7` | `check.sh` **退 0**（客户端 1018/1018） | `NationPolicyPanel.ts`（提案 / 投票 / 公示 / 生效中，只做翻译、**一个判定都不自己下**）+ `GameApi` 三个方法 + `client/tests/NationPolicyPanel.test.ts` | 视觉页见上一行 |
| **V17-H JUnit 半** | `9a3b0cf` | `mvn -f server/pom.xml test` **退 0（213 个 surefire 报告 / 2029 项 / 失败 0 / 错误 0）**；`check.sh` **退 0**。新增 `NationPolicyEndpointTest` 10 条 | MockMvc + test profile（内存存储），**断的是「谁被允许做什么」而不是数值** | **验不到结算与生效**（要 48 小时，夹具没有可控时钟）—— 这一限制写在类注释第一段而不是靠「用例很多」蒙混；那半由 22 条领域用例兜 |
| **V17-H 探针半** | `8fe2f87` | `tools/verify-nation-live.mjs` **退 0（71 通过 / 0 失败，真后端 + 真 HTTP、零夹具）** —— 本格新增 23 条：轮次视图（开局是提案段、8 行国策一次给全、槽位=1、能不能提案/投票各带一句可上屏的理由、槽位竞争规则随视图下发、效果说明由服务端拼好且带中文兵种名、幅度是定点 1500）、内政官提案成功（**裁决 A1 的实际效果**）、普通成员提案被拒（13014）、**13016 与 13015 两枚码按各自的真实时刻各钉一枚**、同一条国策第二次提案被拒（13018）、表里没有的国策被拒（1001） | 真后端；`tmp/backend-policy.log` | 结算与生效那一半验不到（同上） |
| **V17-F 装配** | 本次提交 | `mvn test` **退 0**；`check.sh` **退 0**（客户端 1006/1006）。**`NationPolicyCfg` 的零装配例外随之删除** —— `check-config-consumers` 现跑「42 张表全部在生产代码里被读」 | `NationPolicyBonuses`（乘区 G/H + 产出 + 行军速度的唯一生产方）+ `NationPolicyBonusesTest` | **城墙那一项目前恒为 0**：城墙等级→加成的幅度仍无出处（等策划）。「有生效国策时给多少」的真链路证据要等探针能等 48 小时 |
| **V17-E 端点** | 本次提交 | `mvn test` **退 0**；`check.sh` **退 0**（31 道静态门 + 客户端 **1006/1006**） | `GET /nation/policy`、`POST /nation/policy/propose`、`POST /nation/policy/vote`；6 枚新错误码 13014~13019 | **端点级 JUnit 还没写**（E 格的验收缺一半）—— H 格补上 |
| **V17-D 领域** | 本次提交 | `mvn -pl game-core -am test` **退 0（580 项，其中 NationPolicyTest 22 项）**；`mvn test` **退 0**；`check.sh` **退 0**（客户端 1006/1006）。**`NATION_VOTE_DURATION_HOURS` 从零引用名单里消失了**（`NationRulesAssembler` 现在读它），`check-config-consumers.js` 里那条零引用例外随之删除 | `NationPolicyTest` 22 条，全部落在边界两侧（49% / 50%、参与下限少 1 / 刚好、票相同时提案时刻不同时刻）；`NationRulesAssembler` 装配出 `policyVoteMillis` / `policyRoundMillis` / 门槛 / 参与下限 | **Bot 拒投不在领域层**：`check-no-bot-privilege` 判红过两次（门禁规定「除了 BotRegistry 之外任何地方都不许问这是不是 Bot」），领域签名里的 `isBot` 已全部拿掉，业务代码里的 `bots.isBot(...)` 也换成了注册表新闸门 `mayTakeNationalPolicyAction` |
| **V17-B 配置表** | `247d68e` | `npm run gen` 退 0（42 张表）；`mvn test` 退 0；`check.sh` 退 0（客户端 1006/1006）。**门禁的空转被抓住一次**：第一次跑 `check-config-consumers` 报「41 张表、零装配 0 张」—— 那是因为生成物还没跑，门禁看不见新表，正是 #64 记过的那类「探针必须能区分没有违规和我没看见违规」；生成后复跑才是真相（42 张表、零装配 1 张） | `contract/config/nation_policy.json`（8 行）+ 生成物 `NationPolicyCfg.java` | 生产代码**还没读它**（`UNWIRED` 例外已登记，出处 #477，装配上线的那一刻删） |
| **V17-C 协议** | `d12ad21` + `bc14237` | `npm run gen` 退 0（Java 395 → 407 个文件、TS 同步）；`mvn test` 退 0；`check.sh` 退 0（双端契约一致性 OK，客户端 1006/1006） | 协议 def 22 → 32（国策 11 个）；`NationProtocol.ts` | 只声明不实现：`NationPolicyRoundView` 的每个字段都要有生产方（D/E/F 三格），在那之前它们全是零消费者 |
| **V17 国策** | 本次提交（纯文档） | `check.sh` **退 0**；`mvn -f server/pom.xml test` 退 0 | `B13S4_国策规格草案.md`（8 节：现状核对 / 10 条已定口径 / 11 条裁决 / 4 条 buff 候选表 / 乘区纪律 / 落地清单 / 验收映射 / 台账口径） | 契约、领域、端点、客户端一行都没有，这是刻意的 |

### 2026-10-04 · #753 收尾 + 新机器上从零补环境（本轮）

| 格 | 提交 | 验证读数 | 截图/证据 | 未做 |
|---|---|---|---|---|
| **#753 取消建造（探针侧）** | `03b7521` | `verify-tech-research-runtime.mjs` 城建 **10 项里 9 项由红转绿**，退出码 0 | `client/build/tech-verify/city-cancel-build.png`（取消后木材 4601→4841，退 60%） | **产品码一行未改** —— 根因是量具从未选中那一格（`row===null` ⇒ `CityPanelView.ts:1478` 的 `row !== null &&` 把动作键全置 invisible），修法是探针按 `CityPanel.ts:171` 的 `index = gridY*6+gridX` 算出 `Grid-21` 并 emit 它的 `touch-start` |
| **量具"没起来"不许冒充红** | `266fd510` | 合成双向对照 **8/8**（import 不存在的模块 ⇒ `NO-RUN`；打印读数后 `exit 3` ⇒ 仍记 `3`，没被误吞）；真实场景 `NO-RUN verify-tech-research-runtime.mjs` | `tmp/fake-probes/{verify-modnotfound,verify-realred}.mjs` + 复核命令写在 `tmp/2026-10-04-探针playwright缺失.md` | 只补了 `ERR_MODULE_NOT_FOUND` / `ENOENT…index.html` 两类形状；其它启动期失败（如超时以外的语法错）仍会记成普通红 |
| **playwright 裸 import 治本** | `c963011c` | **59 份探针**改掉写死的 npx 绝对路径（两种形状：静态 `import` 57 份 + `await import` 2 份）；`package.json` 加 `playwright ^1.63.0`；残留 `_npx` 引用 **0**；`check.sh` **退 0**（含探针语法门 `node --check` 覆盖全部 `verify-*.mjs`）；同一份探针的报错由 `ERR_MODULE_NOT_FOUND` **变为** `ENOENT …web-mobile/index.html` ⇒ 解析确实成功 | 62 文件、**59 份各恰好 1 增 1 删**（确认没把行尾整体改写） | 只验了 1 份探针的端到端；全量批跑那部分**当时未跑完**（见下一行） |
| **三条假判据修正** | `20716fba` | `verify-tech-research-runtime.mjs` 由 **通过 49 / 失败 3** 变为 **通过 52 / 失败 0**；反向对照 `tmp/fake-probes/check-overlap-judgment.mjs` **8/8**（超长文案判红 ×2、尾槽留旧数判红 ×2、真读数判绿 ×4）；`check.sh` **退 0** | 同帧截图 `client/build/tech-verify/city-cancel-build.png`：标题 `x∈[635,805]`、队列行 `x∈[557,841]`、一键收割 `x∈[1163,1357]`，**水平区间毫无交叠** | **遗留真隐患未修**：`headerCap` 给满宽 + `SHRINK` ⇒ 文案够长时字形可能滑进右上角常驻键下面（新判据会判红，但彻底消除要动产品码） |
| **city-states 选不中格子** | `0827c669` | 同一台后端同一条命令内跑，**退出码 0**；读数由「暂停键=false / 选择栏=点击建筑查看详情」变为「`emit Grid-7 的 touch-start：true` / 暂停键=true / 选择栏=伐木场 Lv0 · 升级中·25%」 | 同上一张截图 | **没有查清"坐标点击为何打不中格子"** —— 只证明 `emit` 这条路走得通、坐标那条路走不通 |
| **nation-policy-ui 把被吞的读数捞出来** | `1938bca8` | `node --check` 退 0；**真根因待下一轮读数**（本轮只加读数与早停，未改判据） | — | 建盟为什么被拒**尚未查明** —— 此前 `建国被拒 13003「必须先在联盟中」` 是下游症状，真错被整段吞掉 |
| **新机器环境从零补齐 + 开发指南** | `01c3255b` + `5510e942` + `8257be21` + `38eecce8` 等 | 7 层全部补齐（`/d/tmp` · 后端 jar · playwright + Chromium · 仓库根 `npm install` · `client/` `npm install` · Pillow · Cocos Creator 3.8.7）；`client/build/web-mobile` 重建 **298 个文件**、**`BUILD_EXIT=0`**；`bash scripts/check.sh` **退 0** | `client/build/tech-verify/*.png` 五张（研究页 / 提速 / 取消 / 建城） | Cocos 官方 CDN `download.cocos.org` 对那台机器**整域 403**（试过 7 种手段），编辑器是用户装好后我才接手解压与构建 |

**本轮跨格落判据（三条，都写进了代码注释而不只是文档）**：

1. **判"两个 UI 元素压没压住"不能比 `UITransform.getBoundingBox()`** —— 那是布局给的**声明尺寸**，不是字形尺寸。
   `Header`/`Queue` 是**居中** Label、contentSize 被写成满宽（实测 766px 横跨 `x∈[-383,383]`），字形却只占中间一小段
   ⇒ 拿它判"压住"**无论文案多短都恒红**。要么量字形的**保守上界**（非空白字符按 1em 估，CJK 即 1em），
   要么**同时留一张同帧截图**做目视对照。
2. **稀疏数组经 `JSON.stringify` 会把空洞变成 `null`** —— `resourceSlots[i] ?? 'x'` 会把"那一格的节点已不存在"
   （比留空壳更彻底的清空）判成"没清空"。判"是否清空"要接受**下标越界 / 空串 / 空洞**三种形态。
3. **判"某个动作在界面上不可达"之前，先确认量具有没有选中那一格** —— 本轮连续三次都在"被压住 / 状态没进 UI /
   探针时序"之间猜错，三次的真因都是**同一个：`row===null`**。⇒ 必须让量具把 `row` / 节点存在性 / 坐标
   **和同帧截图**一起打出来，光有判据成败不够。

**V10 落地的两处口径决定（留给下一格，别再重新想）**：

1. **埋点：开记录页与翻页都豁免**，登记在 `scripts/check-track-coverage.sh` 的 `SKIP` 里（`openGachaHistory` /
   `turnGachaHistoryPage`）。理由与抽卡面板族完全同口径：`openGachaProbability` 那条写着"一次读、不是玩家意图"，
   而翻页的既有口径见 `rank_view`（"打开了一张榜单；翻页不单独上报"）。
   **过程留痕**：第一版给 `openGachaHistory` 加了 `gacha_history` 事件，被 `check-track-coverage` 判红
   （`turnGachaHistoryPage` 没有埋点）—— 门是对的，改口径而不是给翻页也补一条（后者会让"打开过记录页"的次数被自己的翻页冲垮）。
2. **每页 8 条**（`HISTORY_PAGE_SIZE`）是版式常量，不是业务口径：服务端窗口 50 条由 `GACHA_HISTORY_LIMIT` 决定，
   客户端只决定"一屏画几行"。

**V11 落地的三处决定**：

1. **埋点：`equip_forge` 发事件、进字典**（与 V10 相反，这里是对的）—— 强化是**花资源的玩家意图**，
   不是"一次读"；`check-track-coverage` 现跑 74 个动作全有事件。
2. **「强化」键与行的触摸命中区几何分离**：Cocos 的触摸会派发给所有命中节点，而**引擎声明里没有
   `propagationStopped` 可用**（本轮核过 `client/temp/declarations/cc.d.ts`，零命中）⇒ 行的 UITransform
   锚点挪到左端、宽度裁到键区左沿，底板仍照画满（渲染面不变、命中面分开）。
3. **夹具模式 `EQUIP_STUB=1`**：dev 上没有途径拿到装备，正向链路只能靠 route 夹具注入两件
   （一件能强化、一件满级）。**真后端读数仍打印**（`后端现状：instances=0 heroes=0`），两者不混。
4. **一条只有截图能抓到的缺陷**：首跑 19 条判据全绿，而截图里"强化消耗 铁矿 120"被键压掉一半 ——
   已把第二行的费用也右对齐到键区左沿。**读数全绿 ≠ 版式没问题**，这一格又证了一次。

**V14 落地的三处决定**：

1. **署名原文的单一真源是 `art-src/ATTRIBUTION.md`**：`Credits.ts` 逐字抄它、单测逐字钉住，
   而**探针从 ATTRIBUTION.md 现取那一行**做期望值（不硬编码）—— 事实源没了就判红，而不是探针自己记着。
2. **许可/备注那行也要折行**（`wrapCredit` 按**词**折，超长词才硬切）：作者名被劈成两半是这类页面最不该出的错。
   首跑截图里那行顶出卡片右沿约 8px 而探针当时全绿 ⇒ 已补一条**结构判据**
   （读每个 Label 的 `contentSize.width` 与卡片内宽比），现在它能失败。
3. **"把内部路径印上屏"这条自伤被自己的探针当场抓红**：`Credits.ts` 那条 note 原写了 `art-src/ATTRIBUTION.md`，
   撞上探针里的「屏上不出现内部路径与文件名」判据（照本仓"不许印裸 id"那一族写的）。改成玩家看得懂的话。

**V13-S1 落地的五处决定**（下一格 S2 直接接着用）：

1. **入口挂在联盟页那一行「国家」，不是在国家榜**。理由是 B13 §一：入籍的最小单位是**联盟**，
   没有联盟的玩家走不到建国（服务端 `NationAppService.found` 第一句就 `allianceOf(playerId).orElseThrow`）
   也走不到入籍 ⇒ 那个位置反而是唯一说得通的位置。**探针第 0 场就是这个的对照组**：
   dev 新号没有联盟，联盟页理应**看不到**那一行 —— 看得见才说明入口被硬画出来了。
2. **可加入的国家列表取自国家榜**（`GET /rank/list?type=NATION`），**不是新开一个 `/nation/list`** ——
   服务端 `NationController` 只有 11 个端点，没有 list（现跑核过）。榜的 `id` 就是 nationId、
   `name` 就是国名，两样都是服务端下发 ⇒ 客户端一份表都不用抄。面板因此写「不是全部」。
3. **"不在任何国家"是业务拒绝 13000，不是网络失败**：`AppRoot.openNation` 按 `kind === 'biz' && code === 13000`
   折成 `nation: null`（面板起点），其余失败走提示。合并处理的后果是"断网时看见一张'你还没有国家'的表单"。
   `NATION_NOT_FOUND = 13000` 是 `AppRoot` 上的一个私有常量，注释写了为什么必须与网络失败分开。
4. **权限只由三个服务端下发字段判**：`kingId`（我是不是国王）、`myOffice`（有没有官职）、`allianceCount/memberCap`。
   **客户端没有也不该有一张"谁能干什么"的本地权限表** —— 真正的位在 `role_permission` 表里，客户端拿不到。
   于是「解散」按 `kingId === playerId` 亮、「国库支出」按 `myOffice !== null` 亮，其余交给服务端拒。
   **灰键一律不挂 `touch-start`**（`button()` 的 `enabled` 分支），所以"灰"与"点了零请求"在结构上是同一件事，
   探针的判据就是数 `POST /nation/*` 请求条数。
5. **官职与支给对象都走客户端本地化表**：`OFFICE_LABELS` / `SINK_LABELS`（契约里 `myOffice` 的注释
   **明确要求**客户端据此查表），玩家 id 一律经 `operatorLabel` / `payeeLabel` 换词，换不到给
   「未知成员 / 未知操作人」而不是印 `player:xxx`。探针对 `nation_` / `player:` / `sink:` /
   枚举原文逐条判"屏上不许出现"。

**V13-S1 踩到并修掉的三个版式坑（截图才看得见的那一族）**：

1. **卡片必须只建一次，不能跟着 `redraw` 重建**：输入框按名字缓存（重画时不重建，重建会把键盘焦点吃掉），
   而卡片每次新建就会变成**输入框之后添加的兄弟节点** ⇒ 第二帧起卡片压住三个输入框，
   屏上只剩标签没有框，**而当时探针 68 条全绿**。已把卡片挪到 `onLoad` 里建一次。
2. **同一段里只回显一次"还差什么"，且那句要独占一行**：与按钮同行时它会顶出卡片右沿（V14 同款）。
3. **支出表单开着时收起概况那六行**：600 逻辑高塞不下「概况 + 国库 + 操作 + 表单」，
   开着表单时玩家要的正是"这笔钱花给谁"，那六行此时是重复信息。
   同时 EditBox 的文字必须**手动钉在框里**（EditBox 只在自己的 resize 事件里重算那块矩形，
   而这里全程没有 resize）—— 否则打出来的字飘在框的上沿外侧。

**V13-S2 落地的四处决定**：

1. **外交页没有只读入口这件事，照实说而不是编一张表**：`NationController` 的 11 个端点里
   **没有 `/nation/relations`**（现跑核过），`allRelations` 只在 `POST /nation/diplomacy` 的
   回执里。所以这一页第一次打开时给的是「还没有打过一次交道」这句说明 + 一个目标选择器，
   打完一次交道之后那张表才出现。**填一个"中立"当默认值是撒谎** ——
   那会让玩家以为已经签了一份中立条约。
2. **任命只摆四颗官职键，不摆国王与议员**：服务端 `Nation.appoint` 对这两席当场拒绝
   （`NationAppService.appoint` 的注释写明"国王与议员都没有任命路径"）。
   摆两颗按下去必然失败的键就是 UI 在骗玩家。这**不是"客户端判权限"** ——
   权限位在 `role_permission` 表里，客户端拿不到，所以"谁能任命人"仍然交给服务端。
3. **国家科技的文案复用个人科技那两份函数**（`schoolLabel` / `effectAttrLabel` / `FixedPoint.percentText`）：
   契约里明写两个 `TechSchool` / `TechEffectAttr` 逐字段同形、故意不分叉 ⇒ 抄第二份就是留一个分叉口。
4. **切到「科技」才发 `/nation/tech` 那一条**，打开面板不预拉：那是这一屏最贵的一份，
   而大多数玩家只是来看一眼国库。埋点同理 —— `selectNationTab` 进 SKIP（"一次读"的口径）。

**V13-S2 探针抓到的两个真缺陷（都是"探针会红、但单测与类型门不会红"的那一族）**：

1. **把「选中」当 `enabled` 用 = 把选择键的入口自己关掉**：S2 第一版把目标国键与任命人键写成
   `enabled: 当前选中的是我` —— 于是**没选中时它不吃触摸**，玩家第一次点它没反应、永远选不中，
   后面那四颗键也就永远灰着、点了零请求。已把 `button()` 拆成三态
   （灰=不吃触摸 / 选中=金底 / 常亮），并**扫了同族**：
   支出表单的金额预设、落点（PLAYER / 两个 sink）、收款人四颗键都是同一形状，一起拆开。
   探针里补了两条「还没选时那颗键就能点」的反向判据。
2. **成功回执被染成失败红**：目视 `05-diplomacy-set.png` 时看到「已把与 北伐营 的关系记为…」
   是警告红 —— 玩家读到的是"出错了"。已给 notice 加 `noticeTone`（成功金 / 失败红），
   编排层每一次成功显式翻成 `'ok'`，并钉了一条单测。
   同一轮还顺手改掉两处把内部值印上玩家面的地方：研究回执原来印 `techId`
   （现在按 id 去刚拉的科技表里取**服务端下发的行名**），解散与支出回执的金额原来没走千分位。

**V13 真链路那一格：dev-only 提速档（本次裁决选 B）与它照出来的两处缺陷**：

1. **`NewPlayerBoost` 接口 + `DevNewPlayerBoost`（`@Profile("dev")`）**
   （`server/game-web/.../config/`）：两个环境变量 `IRONOATH_DEV_CITY_LEVEL` 与
   `IRONOATH_DEV_START_AMOUNT`，**都不设时与改动前逐字节一致**（模式隔离那条纪律的硬要求）。
   两处注入（`PlayerInitService` 的开局存档 + `CityAppService` 的主城建筑）——
   **只抬一处会造出"存档 16 级 / 主城建筑 1 级"的自相矛盾存档**，而那一行原有的注释正写着二者必须一致。
   "prod 读不到它"是**结构事实**：prod 上下文里这个 bean 根本不存在 ⇒ `ObjectProvider` 空 ⇒ 走配置表。
   `DevNewPlayerBoostTest` 四条把它钉住：覆盖只在 `> 0` 时生效、空实现安全、
   **`@Profile("dev")` 注解必须在（反射断言）**、两个服务都接了同一个口（按类型找字段，改名不会假红）。
   为什么不改配置表：`INIT_CITY_LEVEL` 与 `resource.initAmount` 是真源，被
   `ConfigRegistryTest`/`PlayerInitTest` 钉着，而且 **prod 与 dev 读同一份表** ——
   为了本地好跑而把 prod 的开局数值一起改了，是拿验收环境换一条测试。

2. **真链路回读屏照出一处显示缺陷：裸 token 的对手方落进了回退语**。
   周税入账那一笔的 `counterparty` 是**裸 token `weekly_tax`**（不带 `player:` / `sink:` 前缀），
   而 `payeeLabel` 只认那两种前缀 ⇒ 屏上显示「其他用途 · 10,000」——
   一笔**入账**被写成"用途"，读起来像钱花掉了。已加 `PLAIN_PAYEE_LABELS`
   （`weekly_tax` / `disband_writeoff` / `war_loot`）与**独立的**回退语 `UNKNOWN_PAYEE`（「其他」，
   与 `sink:` 那侧的「其他用途」分开）。**为什么两轮夹具探针都没照出来**：
   夹具的流水是我们自己编的，里面恰好没有周税那一笔 —— 已把周税补进
   `verify-nation.mjs` 的夹具并加了两条判据（S1 探针因此从 68 条涨到 70 条）。
   这一条是"真链路验收"这个动作本身的产出：**夹具验的是我们想到的形态，真后端会给没想到的那些。**

3. **真链路脚本自己的三处坑（留作同类脚本的教训）**：
   ① `/player/init` 的 `clientTime` 是必填，传 0 会被 `PARAM_INVALID(1001)` 挡住；
   ② dev 内存后端**跨轮次不清**，写死联盟名/标签第二轮撞 `ALLIANCE_NAME_TAKEN(10016)`、
   留下的国家还会把 `NATION_MAX_PER_KINGDOM=4` 占满（撞 `NATION_CREATE_LIMIT(13002)`）⇒
   本轮名字加时间戳 + **跑完自己清掉建过的国**（国王退国后仍亡得了自己的国）；
   ③ 断言**按下标**取关系表/流水在共享后端下是错的（表里有前几轮的残留）⇒ 改成按 id 查。

> 记录纪律：写「本轮命令 + 退出码 + 覆盖范围」，未验证的显式标注「未验证 + 原因」；
> 同一行里「已落地并验证」与「未做」分开写。

### 2026-10-04 12:1x–12:4x 全量批跑分诊（接上表）

| 格 | 提交 | 验证读数 | 截图/证据 | 未做 |
|---|---|---|---|---|
| **nation-policy-ui 认出前提不足** | `918e48b9`（读数本身在 `1938bca8`） | 新加的读数给出答案：`建盟：code=10013 msg=联盟尚未解锁 detail=需要主城 10 级，当前 1 级`。本探针建的是新号（主城 1 级），**建盟要主城 ≥10** ⇒ **量具前提没架对**，不是功能坏 | — | 只加了**退 2 + 说清要什么**，**没有**在开了提速档的后端上重跑过一次来证它真能过（批跑后端当时不带 `IRONOATH_DEV_CITY_LEVEL`） |
| **city-multi-types 点击命中矩阵** | —（**未改**） | 读数：Grid-7 / Grid-11 → 选择栏是「点击建筑查看详情」（没选中）；Grid-31 / Grid-35 → 选中「**主城 Lv3**」（而 Grid-35 实测就是铁矿场）。坐标换算与 `city-states` 里**能用的那个 `clickNode` 逐字相同** ⇒ 不是换算公式的问题 | `client/build/art-verify/13-city-multi-types.png`：表头写「内城 · 建筑 **5/36**」，而画面上**只画出了主城**，另外 4 栋不可见 | ⚠️ **未定**。「另 4 栋没渲染」只是与读数自洽的**假设**，**未验证**：那个橙色椭圆经查**不是探针画的**（全文无 `ellipse`/`Graphics`/`debug`），应是背景美术的广场环路。**下一格要用节点级读数定**：每格的 `frameName` + 世界坐标 + 屏幕坐标 + 该点实际命中了谁。⚠️ **这一格不能照抄 `city-states` 的 `emit` 修法** —— 它测的正是"点击命中矩阵"，换成 `emit` 就把它要测的东西测没了 |
| **panel-reachability 退 2** | —（**未改**） | 读数原文：`[nonpaging][NO-RUN] city 对照组读错（on=true off=true parked=false）——量具未校准，读数作废` ⇒ **它自己就退 2 了**，正是本仓"量具没架对"的约定 | — | ⚠️ **批跑汇总不认这个码**：退 2 被算进「需看的份数」并触发 RERUN（白跑一次）。`run-runtime-probes.sh` 的排除正则只放过 `0 `/`SKIP `/`TIMEOUT `/`# `，**退出码 2 没在里面** —— 与探针自报的 NO-RUN 语义冲突，待统一 |

### 2026-10-04 12:4x 全量批跑收口（57 份跑完）

**底数**：`BATCH_EXIT=0`、后端就绪 3s。**57 = 绿 44 + 需看 5 + 超时 1 + SKIP 7**。
⚠️ **`NO-RUN = 0`** —— 这正是本轮要验的那件事：探针改裸 `import 'playwright'`（`c963011c`，59 份）
在**每一份**身上都成立，没有一份是"没起来"。

| 格 | 提交 | 验证读数 | 截图/证据 | 未做 |
|---|---|---|---|---|
| **ws-runtime 自带旗重跑** | 本次提交 | 修前：裸 `node` 跑死在第一道守卫「本进程的 node 没有全局 WebSocket（PATH 上的 node 是 v20）」。修后：**越过了 WebSocket 关、越过了 `WS_VERIFY_BACKEND` 关**，一路走到第三道「后端不可达」（`EXIT=2`）⇒ 带旗重跑确实生效 | `D:\tmp\probe753\ws2.log` | **没在活后端上跑完一次**（只验到"三道关的第三道"）；批跑重跑时才会真正量它 |
| **perf 那份 TIMEOUT 是我造成的** | —（**未改**） | `TIMEOUT verify-perf-runtime.mjs`。根因：`run-runtime-probes.sh` 的注释明写这份要 `PERF_SOAK_SECONDS` 600（泡机取样）+ 启动收尾 ⇒ **660 秒以上**，而我给 `RUNTIME_PROBES_TIMEOUT=420` ⇒ **每批都必然假 TIMEOUT**（脚本里连"实测 300 秒时正是如此"都写着） | — | ⇒ **下一格起批跑一律用脚本默认的 900**，别再自己压低 |
| **批跑认下退出码 2（PREREQ）** | 本次提交 | 合成双向对照 **2/2**：`exit 2` 的合成探针 ⇒ 归 **`PREREQ`**、**不触发 RERUN**、**不进「需看」**、末尾单列；`exit 3` 的合成探针 ⇒ 仍记 `3` 且**仍触发 RERUN**（`首跑=3 复跑=3`）。汇总行读作 `需看的份数 = 1 … 前提不足 PREREQ = 1` | `tmp/fake-probes/verify-prereq.mjs` + `tmp/fake-probes/verify-realred.mjs`，复核命令见 `.qoder-work-queue.md` | **没在真实探针上验过**：下一次批跑才会看到 `verify-panel-reachability` 与 `verify-nation-policy-ui` 真的落进 PREREQ 那一栏（这两份现已都退 2）。⚠️ 本仓还有别的探针可能退 2 表达**别的东西**（真红也可能是 2），那份名单**尚未逐份核对** |

**一条顺带的正读数**：`# RERUN verify-plate-plant.mjs 首跑=1 复跑=0 判定取复跑` ——
首跑红、复跑绿，说明它**有抖动**；批跑那个"非零补跑一次、两次都红才判红"的机制**在这一份上救了场**。
⚠️ 但这也意味着**"这份绿"不等于"它稳"**：抖动的那一次没人看见，只有 `# RERUN` 行记着。

### 2026-10-04 13:0x city-multi-types 根因定案：**量具缺陷**（不是产品缺陷）

补了节点级读数（每格的世界坐标 / 尺寸 / 锚点 / 图标节点位置 / 算出的页面坐标）后一次跑定案。

**几何读数**（视口 1440×900）：

| 格 | 世界坐标 | 图标尺寸 | 算出的页面坐标 | 在视口内？ |
|---|---|---|---|---|
| Grid-7（农田） | (−151, 61) | 112×112 | **(−226**, 809) | ✗ 左出界 |
| Grid-21（主城） | (480, 455) | 132×132 | (720, 218) | ✓ |
| Grid-31（兵营） | (299, −188) | 102×102 | (448, **1182**) | ✗ 下出界 |
| Grid-11（仓库） | (1232, 201) | 100×100 | (**1848**, 599) | ✗ 右出界 |
| Grid-35（铁矿场） | (1249, −167) | 96×96 | (**1873, 1151**) | ✗ 右下出界 |

反推比例自洽：`page_x = 1.5 × world_x`（480→720、1232→1848 都吻合）、`page_y = 900 − 1.5 × world_y`
⇒ **可见范围只有 world x∈[0,960]、y∈[0,600]**。列间距实测 `(1232−(−151))/4 ≈ 346` 世界单位，
而**格子本身只有 60 宽** ⇒ 6 列铺开约 1890 单位，**远超可视宽度**。

**根因**：城面板**本来就设计成可缩放可平移**（`CityPanelView.ts`）：
`private zoom = CITY_ZOOM_DEFAULT`（`:255`，**`CITY_ZOOM_DEFAULT=1.8` / `MIN=1` / `MAX=2.4`**）、
滚轮缩放（`:552`）、双指捏合（`:568`）、单指拖动 + `setFocus`（`:583`）、`stage.setScale(zoom,…)`（`:646`）。
⇒ **网格超出视口是设计如此**，玩家拖一下就能看到别的格子。
**探针却假设"格子都在屏幕上"直接点基座中心** ⇒ 那些格子此刻根本不在视口里。

**顺带解释了那个怪读数**「Grid-31/35 选中主城」：Playwright 会把视口外的点击坐标**夹进视口**，
于是夹完正好落在主城头上 —— **不是串格，是夹出来的**。

⚠️ **未做（下一格）**：探针侧补"点之前先把镜头移到那一格"（`setFocus` 或先缩到能看见），
再点基座中心。**产品码一行不用改**（可平移可缩放就是设计）。
⚠️ 且这条**尚未在探针里实现**，本轮只落了读数；也**没有**回头核其它"点某个格子"的探针
是否有同一个前提错误（`verify-city-states` 已在 `0827c669` 改成 `emit touch-start`，
但那一份**测的不是命中矩阵**，改法不同、结论也不同）。

### 2026-10-04 13:3x city-multi-types 修完：**EXIT=0**

探针侧补"点之前先 `setFocus` 把那一格摆到屏幕中心"，**产品码一行未改**。

- **怎么拿到 view**：`@ccclass('CityPanelView')`（`CityPanelView.ts:242`）已按名注册 ⇒
  遍历场景 `n.getComponent('CityPanelView')` 即可取到实例。
- **焦点怎么算**：由 `applyStageTransform`（`:641-651`）反推 —— 世界坐标 `= zoom × (local − focus)`
  ⇒ 想把某格摆到屏幕中心就 `setFocus(该格的 local 坐标)`（读 `node.position`）。
- **⚠️ 顺序是硬要求**：**坐标必须在移完镜头之后重算**。镜头一动，所有格子的页面坐标全变；
  上一版"算一次就往下点"的做法即使加了移镜头也仍然是错的。

**验（同一台后端、视口 1440×900）**：移镜头后五个格子的页面坐标全部落回视口内 ——
`Grid-7 → (337,450)`、`Grid-11 → (1259,450)`、`Grid-31 → (720,462)`、`Grid-35 → (1284,450)`、
`Grid-21 → (720,218)`（主城原本就在中心，故不动），**命中矩阵全过，EXIT=0**。
（`y=450` 那几行正是被摆到屏幕中心的 y，视口中心 450 ✓。）

⚠️ **未做**：
- 读数里 `节点世界` 那一列是**移镜头之前**取的（场景快照只做一次），现在只作参照、**不作判据**；
  若要拿移镜头后的世界坐标，得在移镜头之后再取一次快照。
- **没回头核其它"点某个格子"的探针**是否有同一个前提错误（面板可平移缩放 ⇒ 点之前可能都得先移镜头）。
- 没配"点击命中矩阵"的反向对照（"点空格子不许串到邻格"那一相已有，但它用的是**移镜头前缓存**的坐标，
  同样的顺序问题，需要下一格一起理）。

### 2026-10-04 13:4x city-multi-types 收尾：负向对照也走同一入口

把"点某一格"抽成**唯一入口** `clickTile(name)`：先 `setFocus` 把那一格摆到屏幕中心 → **重算**页面坐标 → 再点。
命中矩阵与负向对照**都走它**，杜绝"某一处忘了移镜头 / 用了旧坐标"。

**负向对照原先的两个毛病**（都已修）：
1. 空格多半**不在当前视口里**（面板可平移缩放）⇒ 不移镜头就被 Playwright 夹坐标，点到哪一栋全看夹完落在哪；
2. 它把**所有格子**的页面坐标一次算完就缓存 —— 一旦中途移了镜头，那批坐标全部作废。
（「离所有建筑最远」仍按页面坐标算距离，但那只是**挑谁**用的启发式、**不参与判据**。）

**验（EXIT=0）**：
```
[geo] 空格 Grid-28 距已建格 644px 移镜头=[115,-48] 点下去时的页面坐标=(720,450)
负向对照：点击前「主城 Lv3」→ 点击后「主城 Lv3」
```
⇒ 空格被摆到屏幕中心 `(720,450)`，点完标题**不变**（"点空了"，而不是"串到邻格"）—— 正是这条判据要的分辨力。

⚠️ **仍未做**：读数里 `节点世界` 那列仍是移镜头前取的、只作参照不作判据；
**其它"点某个格子"的探针仍未逐份回头核**是否也有"面板可平移缩放 ⇒ 点之前要先移镜头"这个前提错误。

### 2026-10-04 14:0x 更正上一格：**「移镜头就够了」是错的**，夹取才让它看起来成立

回头逐份核点格子的探针（共 8 份：`verify-city-build-many` · `verify-city-full-city` ·
`verify-city-multi-types` · `verify-city-phone` · `verify-city-states` · `verify-city-zoom-runtime` ·
`verify-gift-popup` · `verify-tech-research-runtime`）时，`verify-city-zoom-runtime.mjs:170-176`
**直接写了这件事**（它是这批里唯一把"落点在不在视口内"当判据的）：

> 主堡在底图上半部（本地 y 为正），1.8 倍下要把它摆到正中就得露出底图之外的深色底，
> 而"底图必须铺满视口"是硬约束（`applyStageTransform` 的夹取上限 `content*(zoom-1)/2`）…

⇒ **`setFocus` 是会被夹的**，"移了镜头就看得见那一格"**不成立**。
上一格我报 EXIT=0 是**运气**：夹完那些格恰好仍在视口内。补读数后一跑，**6 次移镜头里 5 次被夹**：

| 格 | 想要 | 实际（被夹） | 夹取上限 |
|---|---|---|---|
| Grid-7 | `[-355, 0]` | `[-213, 0]` | maxX = 960×0.8/3.6 = **213.3** |
| Grid-11 | `[413, 78]` | `[213, 78]` | 同上 |
| Grid-21（主城） | `[-5, 219]` | `[-5, 133]` | maxY = 600×0.8/3.6 = **133.3** |
| Grid-31 | `[-106, -138]` | `[-106, -133]` | 同上 |
| Grid-35 | `[422, -126]` | `[213, -126]` | 同上 |
| 空格 Grid-28 | `[115, -48]` | `[115, -48]` | 未被夹 |

数字与 `verify-city-zoom-runtime` 记的公式**逐个对上** ⇒ 那份探针的读数可信。

**因此把 `clickTile` 补成三段硬要求**（①先移镜头再算坐标 ②**夹取要能测出来** ③**落在视口外就不点**）：
- 读 `view.focusX/focusY` 与 `setFocus` 的入参比对，`clamped` 直接打进日志；
- 算完落点**必须在 canvas 矩形内**才点 —— 视口外就**先缩到 `CITY_ZOOM_MIN=1` 再试一次**（可平移范围最大），
  仍在外就记 `still-outside` **不点**；
- 理由同前：Playwright 会把视口外坐标**夹进视口**，点空或落到主城头上都是这么来的
  ⇒ **宁可这一格报"没量到"，也不要量一个夹出来的假读数**。

**验**：`EXIT=0`，命中矩阵与负向对照仍全过，且日志现在能分辨"这一格是正常点的"与"这一格被夹后才点的"。

⚠️ **未做**：其余 7 份点格子的探针**仍未改**——它们各自点的是 HUD 键 / 主城 / 自己那几格，
是否踩到同一个前提错误要**逐份跑**才知道（`verify-city-phone` 是手机视口，换算更容易错，优先）。
本轮只把 `city-multi-types` 这一份做成了可复用的样板。

### 2026-10-04 14:1x 样板推到 verify-city-phone（手机视口）：EXIT=0，**且真夹了一次**

把「先移镜头 → 落点必须在视口内 → 触摸」那段搬进 `verify-city-phone.mjs`（它点的是**一格**：伐木场 `Grid-7`）。

⚠️ **手机视口下同一个前提真的发作**（1440×900 那份是运气才没被夹到）：

```
[phone] Grid-7 want焦点=[-260,0] 实际焦点=[-213,0] 被夹=true 视口=960x440 落点=(395,220) 在视口内=true
```

⇒ 视口 960×440 下 `Grid-7` 想移到 `x=-260`、被夹到 `-213`；移镜头后落点 `(395,220)` 落在视口内，
`touchscreen.tap` 点到「取消」、`/city/*` 新增 `[cancel]`、页面报错 0 条 ⇒ **全绿**。

⇒ **判据没变松**：落在视口外时这一格**不点**、直接退 2 并说清
「要跑在能看见那一格的视口/倍数上，否则量的是'夹完落在哪'，不是'点这一格会怎样'」。

**8 份点格子的探针现状**：`city-multi-types` ✅ 已修 · `city-phone` ✅ 已修 ·
`city-states` ✅ 已改 emit（`0827c669`，但它测的不是命中矩阵）· **余 5 份未跑**
（`city-build-many` · `city-full-city`〔SKIP，需 ART 凭据〕· `city-zoom-runtime`〔它自己就把"落点在不在视口内"当判据〕· `gift-popup` · `tech-research-runtime`）。

### 2026-10-04 14:3x 余下 3 份连跑：**全绿，但绿的原因是「绕开」不是「验过」**

`city-build-many` · `gift-popup` · `tech-research-runtime` 连跑 ⇒ **`需看的份数 = 0`、`BATCH_EXIT=0`**。
但逐份看读数，**三份的 `setFocus` 出现次数都是 0**，也就是说**它们一个都没移镜头**，绿的原因各不相同：

| 探针 | 它实际怎么做的 | 判定 |
|---|---|---|
| `gift-popup` | 在 **Grid-1** 发起建造，之后只**读屏**不点格子 | ✅ **真安全** —— 本来就不依赖"点某一格" |
| `city-build-many` | 在 Grid-2 / Grid-9 发起建造（走 HTTP 不点格子），之后读屏 | ⚠️ **靠"不点"绕开** —— 一旦它要点某一格就会踩同一个坑 |
| `tech-research-runtime` | `03b7521` 已改成 `emit touch-start` | ⚠️ **靠改法绕开** —— `emit` 不经命中区，它测不到命中 |

⇒ **判据不能只看退出码**：这三份绿，是因为它们**没走那条会出错的路径**，不是因为那条路被验过了。
这正是本轮那三份已修的探针（`city-multi-types` / `city-phone`）给出的对照 ——
它们**真的去点**，才暴露出"网格可平移缩放 + 焦点会被夹"这两条。

⇒ **落判据**：给"点格子"类探针分类时，除了看它点不点，还要看它**靠什么点**
（HTTP / `emit` / 坐标）—— 后两者**都绕开命中区**，坐标那一种还要额外满足"先移镜头且落点在视口内"。
**读数里出现"全绿"时，先确认它有没有真的走过那条会出错的路径。**
