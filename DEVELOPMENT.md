# 开发指南 · PROJECT_IRON_OATH

> 本文档回答一个问题：**拿到这个仓库，怎么把它跑起来、怎么验证它是对的。**
> 设计原则见 `B00_共享上下文.md` 与 `C00_核心设计原则总纲.md`，本文件只讲操作。

---

## 一、环境要求

| 组件 | 版本 | 必需性 |
|---|---|---|
| JDK | **17**（不是 8，不是 21） | 必需 |
| Maven | 3.8+ | 必需 |
| Node.js | 20+ | 必需（客户端类型检查与逻辑单测） |
| MongoDB | 4.2+ | 仅 `storage=mongo` 时需要；默认 `memory` 不需要 |
| Redis | 6+ | B07 起需要（行军延迟队列）；B01~B06 不需要 |
| Cocos Creator | **3.8.7**，装在 `D:/Cocos/Creator/3.8.7/CocosCreator.exe` | 要跑 `build-webmobile.sh`／真机探针时需要；逻辑层单测不需要 |
| Playwright（模块 + Chromium） | 任意近期版本 | 要跑 `tools/verify-*.mjs` 里的浏览器类探针时需要 |
| Python + Pillow | Python 3.x | 必需（`scripts/check-icon-legibility.sh` 读图要 `PIL`；缺它 `check.sh` 会红在 `[legibility][FAIL-前置] 缺 Pillow`） |
| make | 任意 | 可选。Windows 上通常没有，改用 `npm run <target>` |

**Windows 注意**：仓库脚本假设 Git Bash。若 `JAVA_HOME` 指向 JDK 8，
`scripts/env.sh` 会自动在常见安装路径里找 JDK 17 并覆盖它，无需手工切换。

**新机器上第一次跑探针，容易漏的三样**（2026-10-04 在一台全新机器上实测逐层踩到）：

1. **Playwright 是仓库根的 devDependency，根目录 `npm install` 会带上它。**
   `tools/verify-*.mjs` 里的 import 是裸 `from 'playwright'`，由 node 的常规解析找到它。
   ⚠️ **历史坑（2026-10-04 修掉）**：此前 59 份探针把 import 写成了绝对 `file://` 路径，指向一个 npx 缓存目录
   （形如 `file:///D:/Java/nodejs/node_cache/_npx/<hash>/node_modules/playwright/index.mjs`）。
   那条缓存一没（新机器 / 清缓存 / 换用户），**整批浏览器类探针同时失效**；
   更糟的是当时 `run-runtime-probes.sh` 的旧判据会把"模块找不到"记成**一次普通的红**、冒充产品缺陷。
   现在两处都已修：import 改回裸 specifier + 依赖声明进 `package.json`；批跑对"模块找不到 / 产物缺失"一律记 `NO-RUN`。
2. **`client/build/web-mobile` 是构建产物，探针直接读它。** 没构建过就会以
   `ENOENT ... client/build/web-mobile/index.html` 失败 —— 那不是产品缺陷，是产物没建。
   构建：`env -u ELECTRON_RUN_AS_NODE bash scripts/build-webmobile.sh`
   （**必须先 unset `ELECTRON_RUN_AS_NODE`**，否则 CocosCreator 会以 node 模式启动并报 `bad option: --project`）。
3. **`/d/tmp` 是批跑账本与日志的默认目录**（`RUNTIME_PROBES_OUT` / `RUNTIME_PROBES_LOGDIR` 的默认值都在它下面）。
   新机器上它不存在时，账本写不下去。

---

## 二、四条命令

```bash
npm install                 # 首次：安装客户端 typescript 与 @types/node
cd client && npm install    # 客户端依赖（与根目录分开）

npm run gen      # 契约/配置表代码生成（改完 contract/ 后必须跑）
npm run check    # 分层纯净性 + 双端契约一致性（CI 门禁）
npm test         # 服务端 JUnit + 客户端 node:test
npm run build    # 上面三条 + 编译打包 + 客户端 strict 类型检查
npm run dev      # 启动服务端（dev profile，内存存储，零外部依赖）
```

等价的 `make dev / build / test / check / gen` 也提供了，二者都委托 `scripts/*.sh`。

---

### 运行批次退出码（#2367，2026-10-23）

`run-runtime-probes.sh` 只在账中每份探针退出0时返回0；断言或执行失败返回1；TIMEOUT、PREREQ、NO-RUN、SKIP、无效状态或空批返回2。失败与未验证同时存在时返回1，完整退出码账仍逐项保留。首跑与复跑使用相同归类；清单最后一行即使没有换行也必须执行。各量具内部范围仍按其结构化证据审查，退出0不能推成全项目验收。

已有账本可只读重算，无需再发业务请求：`bash scripts/run-runtime-probes.sh --summarize-status <退出码账路径>`。双后端包装器保存子退出码并在清理后传回；清理未确认则返回1。其真实模式的旧“按端口杀进程”策略尚未收紧，本格只验证无后端/无端口动作的收尾自检；并行会话仍使用已核验自有PID/库/端口的隔离启动与清理。

真实国战量具只有读到SIEGE或非作废SETTLED才验证围城结算/季榜；PREPARATION不能用宣战时刻起等90分钟。未验证项保留NO-RUN并返回2。疲劳相复用已确认活跃战事，仅明确SETTLED才创建第三国；读取失败/缺失/未知状态停止该相的新业务写入。

## 三、验收项怎么跑

| 命令 | 覆盖的验收项 |
|---|---|
| `npm test` | PRNG 可复现性、fork 隔离、定点数精度、时间校准、配置校验、`/player/init` 全链路 |
| `npm run check` | 分层纯净性（core 层无框架依赖）、双端契约一致性 |
| `bash scripts/verify-b01.sh` | 对**已启动**的服务端跑 HTTP 层验收（需先 `npm run dev`） |

`scripts/verify-b01.sh` 的注意点在脚本头部：Windows Git Bash 会把命令行内联的中文按 GBK
发出去，所以请求体一律走 UTF-8 临时文件。直接 `-d '{"nickName":"中文"}'` 会收到
`Invalid UTF-8 middle byte` —— 那是终端编码问题，不是服务端 bug。

---

## 四、存储实现切换

```bash
# 内存存储（默认）：零外部依赖，重启丢档，用于开发与单测
npm run dev

# 真实 MongoDB（全部 18 个存储端口已有 Mongo 实现，2026-09-12 起可直接启动）
# 启动日志里会有一条「mongo 存储自检通过：18 个存储端口全部是 MongoDB 实现」——
# 那是 MongoStorageGuard 在逐端口核对实现类：任何一类退回内存实现都会拒绝启动并点名。
# 两步是必须的：命令行调用 spring-boot:run 会在 reactor 的每个模块上各执行一次，
# 父聚合工程没有主类 ⇒ 直接 "Unable to find a suitable main class"；而只给 -pl game-web
# 又会用本地仓库里的旧上游 jar，所以先 install。
mvn -f server/pom.xml -pl game-web -am -DskipTests install
mvn -f server/pom.xml -pl game-web spring-boot:run \
  -Dspring-boot.run.profiles=dev,mongo
```

`ironoath.storage` 只有 `memory` 与 `mongo` 两个值。两套实现被刻意做成**语义等价**：
读写都返回副本、`insertIfAbsent` 靠唯一约束保证原子性、`save` 做乐观锁版本比对。
否则单测在内存实现上过了、上线在 Mongo 上炸，而这种 bug 只在并发时出现。

启动时会自动确保全部索引存在并打日志（`MongoIndexes.ensure`，契约测试跑的是同一份定义）：

- `player.deviceId` 唯一索引 —— 这是「同设备只建一个号」的**唯一**保证。
  索引缺失时 `insertIfAbsent` 会静默退化成「永远插入成功」，等于给刷资源开了门。
- `request_id.expireAt` TTL 索引 —— 回收幂等键，否则该集合无限膨胀。
- `social_squad.name`、`social_alliance.name` / `.tag` 唯一索引 —— 组织名与标签的全局唯一
  在并发下只能靠索引成立（内存版靠一把粗锁）。`members.playerId` 服务「我在哪个组织」。
- `march_due.dueAt` —— 行军到期扫描走它；队列进 Mongo 是 B07 验收 1（重启后队伍按真实剩余时间继续）
  的前提，内存队列一旦随进程消失，在途队伍会停在半路。

---

## 四之二、dev 提速档（跑真机探针 / 截图前必读）

B13 的国策一轮要 **48 小时**（24h 投票窗 + 24h 生效段），赛季与周税同理。
不提速就只能靠拨钟的单元测试 —— 而「文本断言全绿而画面是坏的」在本仓已发生过不止一次。
三个环境变量把 48 小时压成几分钟，**没有后门端点、没有第二个时间源**（`DevClockSpeed` 是
`ClockSource` 的实现，结算/开窗/到期全走同一套代码）。

| 环境变量 | 作用 | 不设的后果 |
|---|---|---|
| `IRONOATH_DEV_CITY_LEVEL` | 新号主城等级（探针要 16，建盟门槛是 10） | 建盟报 `10013 联盟尚未解锁：需要主城 10 级，当前 1 级` |
| `IRONOATH_DEV_START_AMOUNT` | 每种资源初始量（建盟还要金币 500，新号只有 200） | 建盟报 `10014 金币不足` |
| `IRONOATH_DEV_TIME_SPEED` | 时间倍速（上限 100000；投票段要 48 小时真实流逝） | 探针打印「跳过投票段…这一段是未验」，**但仍然退 0** |

**两条必须知道的坑**：

1. **必须用 `--spring-boot.run.profiles=dev`。** `DevNewPlayerBoost` 与 `DevClockSpeed` 都是
   `@Profile("dev")` —— **起 `local` profile 时这两个 bean 整个不存在**，城市档与时间档**静默失效**
   （只有 `DevNewPlayerBoost` 开档时会打一行 INFO，日志里能看出来；`DevClockSpeed` 同理）。
2. **`IRONOATH_DEV_TIME_SPEED` 要给「后端」和「探针进程」两边都设。** 探针
   `tools/verify-nation-policy-ui.mjs:393`（行号 2026-10-07 现跑）读的是**自己进程的环境变量**，不是后端的 ——
   只给后端设会被判成「未设」并跳过投票段。

一次跑通（B13 国策收官，2026-10-01 实测 41 通过 / 0 失败）：

```bash
# 1) 后端（三个档 + dev profile）
source scripts/env.sh
IRONOATH_DEV_CITY_LEVEL=16 IRONOATH_DEV_START_AMOUNT=100000 IRONOATH_DEV_TIME_SPEED=3600 \
  mvn -f server/pom.xml -pl game-web spring-boot:run -Dspring-boot.run.profiles=dev

# 2) 产物（先构建再量测，两者不能并行）
env -u ELECTRON_RUN_AS_NODE bash scripts/build-webmobile.sh

# 3) 探针（后端与探针两边都要有 TIME_SPEED）
export BACKEND_ORIGIN=http://localhost:8080 IRONOATH_DEV_TIME_SPEED=3600
node tools/verify-nation-policy-ui.mjs
```

**验收纪律**：**探针退 0 不等于全部验过。** 凡「条件不满足就跳过」的分支，必须核那段到底跑没跑
（看输出里有没有「跳过」二字）。截图另存到 `tmp/` 下，**要打开看一眼** ——
自动断言只读 DOM，读不出「画出来是黑的」。


## 五、改配置表的正确流程

```
1. 改 contract/config/*.json（或 B02 之后的 xlsx 源表）
2. npm run gen            # 重新生成双端类型；顺带跑一次全量校验
3. npm run check          # 确认生成物已同步入库
4. npm test               # 确认没有把数值改崩
5. 提交（生成物必须一起提交，CI 靠 diff 校验同步性）
```

配置表的三条硬规则：

1. **小数一律写成十进制字符串**（`"1.18"`，不是 `1.18`）。JSON number 是 double，
   写 `1.18` 实际存的是 `1.1799999999999999378…`，直接违反「禁止 float/double 参与结算」。
   校验器会拒绝小数字段写成 number。
2. **每个数值都要有 `why`**，说明为什么是这个数。没有出处的数字不许进表。
3. **没定稿的加 `todo`**，启动时会一次性打 WARN 列出来，不会静默混进生产。

改坏配置的后果是**服务端拒绝启动**，并一次性列出全部错误字段：

```
配置表校验失败，服务端拒绝启动。请一次性修正下列全部问题后重启（共 8 处错误）
  1) [resource#WOOD.initAmount] 必须是整数（JSON number），实际="很多"
  2) [resource#STONE.initCap] 缺失：该字段为必填（规则 LONG_NONNEG）
  ...
```

这是刻意的：带着错误配置启动，比启动失败危险得多。

---

## 六、改协议的正确流程

```
1. 改 contract/proto/*.schema.json
2. npm run gen      # 同时产出 Java record 与 TS interface
3. npm run check    # diff 校验双端同步
```

**禁止手改生成物**：

- `server/game-web/src/main/java/com/ironoath/web/dto/generated/`
- `client/assets/scripts/net/generated/`

`scripts/check-contract-sync.sh` 会重新生成一遍并与仓库内副本逐字节 diff，
不一致即构建失败。生成物里不含时间戳，所以 diff 是稳定的。

枚举可以声明 `x-enum-source`，生成器会强制它的取值与某张配置表的 id 集合完全一致。
例如 `ResourceType` 绑定 `resource` 表：配置表加了新资源但忘了改协议，构建直接失败，
而不是等到线上抛 `IllegalArgumentException`。

---

## 七、分层规则（CI 强制）

```
game-web    → 可依赖所有层
game-config → 只能依赖 game-common
game-core   → 只能依赖 game-common    ★ 零框架依赖
game-battle → 只能依赖 game-common    ★ 零框架依赖
game-common → 不依赖任何本项目模块
```

`scripts/check-layering.sh` 检查四件事，任何一件失败即构建失败：

1. `game-common` / `game-core` / `game-battle` 源码中无框架 import
   （Spring、jakarta、mongodb、redisson）
2. 这三个模块的 pom 中无框架依赖
3. 这三个模块不依赖 `game-common` 之外的本项目模块
4. 不使用 `Math.random()` / `new Random()` / `ThreadLocalRandom` / `System.currentTimeMillis()`

第 4 条是为了守住两条铁律：随机必须可复现（走 `Rng`），时间必须由调用方传入
（走注入的 `TimeService`）。否则核心逻辑就无法脱离容器批量跑单测，
而**平衡调不动，数值就永远调不好**。

### game-core 怎么读配置而不依赖 game-config

用依赖倒置，端口定义在 `game-common`：

```
game-common  定义 CurveSource / GlobalParamSource（抽象）
game-config  ConfigRegistry 实现它们（读 contract/config/*.json）
game-core    Formula 只依赖抽象
```

于是 game-core 既拿到了配置驱动能力，又没有依赖 game-config；
单测时直接传一个内存实现，不加载任何配置文件。

**另一条约定**：game-core 的服务方法接受「已从配置解析好的参数」，不在内部查配置。
例如曲线求值接收 `baseFixed`，而不是自己去读建筑表。这样 game-core 永远是纯函数集合。

---

## 八、客户端目录与验证边界

```
client/assets/scripts/
  core/      引擎无关：EventBus、Prng、FixedPoint、TimeSync
  net/       NetModule、传输适配、generated/Protocol.ts（生成物）
  game/      store（单一 Store）、session（登录编排）
  scene/     表现层，唯一允许 import 'cc' 的地方
tests/      逻辑单测（node:test；在 assets 外，Cocos 不会编译它）
```

**能被验证的**：`core` / `net` / `game` / `test` —— `tsc` strict 类型检查 + `node --test`，
在 CI 里真实执行。

**不能被验证的**：`scene/`。它依赖真实 Cocos 运行时，本仓库只提供
`client/types/cc.d.ts` 这个 headless 类型桩让 `tsc` 能过。桩与真实 API 若有偏差，
`tsc` 会通过但编辑器会报错 —— **场景层改动必须在 Cocos Creator 里过一遍**。

---

## 九、双端一致性的可执行保证

不靠自觉，靠三样东西：

1. **契约同源**：Java DTO 与 TS interface 由同一份 JSON Schema 经同一次解析生成
   （`ProtoSchema` 先解析成中立模型，再分语言输出），CI diff 校验同步性。
2. **PRNG 黄金样本**：服务端 `RngTest` 与客户端 `Prng.test.ts` 共享同一组常量
   （`seed=12345` 的前 5 个 uint32 输出）。任何一端改了算法，两边测试同时红。
3. **数值语义对齐**：定点数 `SCALE=10000`、HALF_UP 舍入、时间偏移的
   `rtt/2 补偿 + 加权移动平均 + 抖动剔除`，两端各有单测锁定同一组断言。

注意客户端定点数的能力边界：JS 没有 64 位整数，所以客户端 `range` 只支持 32 位区间，
`geometric` 是逐步舍入的近似值（服务端用 BigDecimal 一次舍入）。
这是可接受的，因为**客户端从不结算** —— 需要与服务端逐位一致的数值必须由服务端算好下发。


## 十、活动持久发奖计划的升级与回退边界

新增pendingClaims、free_item和pointClaims之后，必须先停止连接目标数据库的旧写入者，再备份数据库及当前完整JAR/配置，最后启动已经验证的新版本。不得让旧版与新版混写同库：9dabc7e5及之前的材料codec拒读free_item，旧活动文档整份保存会删除其不认识的pendingClaims。代码可读旧数据的回归不能作为旧程序可读新数据的证据。

出现新格式后，程序回退应使用理解并保留新字段及来源的兼容修复版本。直接回到旧JAR、删除新收据、清除待发计划都不是安全回退。若只能整体恢复备份，应停写并恢复应用、配置和数据库的一致切点，事先明确如何恢复升级后的玩家进度；不能声称没有数据影响。新版仍需完成待发活动与原季积分/档位邮件恢复，不能因计划数暂时为零就断言旧codec可以读新背包回执。

本轮仅本地实现及随机Mongo验收，不执行上述生产切换，也不更新原9490/验收账号/微信IDE。

### Bot画像与延迟动作的存储选择

生产配置使用`ironoath.storage=mongo`、`ironoath.lock=redisson`与`ironoath.bot.task-queue=redis`，Redis地址由`IRONOATH_REDIS_ADDRESS`提供（例如`redis://127.0.0.1:6379`），Mongo连接仍由`IRONOATH_MONGO_URI`提供。dev/test缺省保留内存队列；显式要求Redis而依赖或连接缺失时应启动失败，不降级。此处是源码配置与使用说明，本任务不部署或更新原9490实例。

Bot完整画像存于`bot_profiles`，身份查询直接读同一存储。Redis队列以实际Mongo库名的SHA256作为hash tag，ZSET和载荷HASH同槽；每Bot一游标，包含原pendingDecision和最早到期，首次登记只补不存在的游标。到期项先校验，再按原载荷原子领取；多个客户端不会领取同一项。没有Bot消费轮询或常驻定时器，逾期动作仍由请求/ops tick惰性推进。拉取后进程崩溃不保证该动作重放，不能将这项实现称为全业务状态恢复。

活进程内的存储异常恢复按执行阶段保全：未执行时保原动作和最早到期，已选拟人延迟不重新随机，动作已尝试后只接下一次决策。恢复仓跨规则热更共享，以独立Entry原子领取防并发旧快照重新入队；落队失败仅在没有新游标时归还，重试计入原整轮预算。该恢复仓不持久，进程硬崩溃边界不变。自动测试须有真实Mongo27017及Redis6379，托管CI配置两项健康服务；本地Python无PyYAML时可用现有JAR的SnakeYAML核验工作流，不为此新增依赖。

`bot_behavior`保存回应水位、已问候联盟与申请节流，按字段原子更新；回应水位只前进，同一受击只有一个领取者。`bot_attack_budget`以受害者单文档CAS原子分配待确定战斗占位与确认事实，`bot_attack_ledger`仅保存稳定来源的受害者归属/最近使用元数据；当前版本未部署，不将上一轮未部署中间结构当历史数据迁移。出征只预检，攻城和采集拦截实际开战前取得占位，只有战斗服务明确NotStarted阶段拒绝才释放；攻城及拦截在任何身份下都先以行军CAS保存既有FIGHTING，advance和recall均版本先读再重读快照防旧对象配新版本，召回CAS冲突拒绝；并发扫描不撤FIGHTING事件。战损之后的业务/系统异常保不确定占位并持久隔离行军，退出自动扫描、禁止玩家召回，须核对原结算，不据BizException类型猜未开战；真实战斗后记账故障保占位并在活进程tick惰性补账，真实剩余兵力仍保存返程。占位计入可用额度，但不计已发生攻击事实。规则热更不清事实，窗口严格大于24h起点；已确认过期事实由tick懒清理，不确定占位不自动过期。明确战前异常按当前行军CAS归属安全返程；reserve/释放回执故障只释放本来源pending且惰性补释放，已确认事实不删。拦截守方同样CAS持久FIGHTING，核心输入使用归属前采集快照；明确未开战按已持有版本恢复GATHERING，partial仍持久隔离。已知完整返程、无损返程及采集恢复快照都在第一次保存/改期IO前登记，完整战斗守方附属IO失败不再阻断攻方状态；全灭清理用deleteIfVersion保当前归属，故障惰补清理。已知状态快照保存失败保活进程快照和归属版本，下次请求CAS补写；不同归属/版本不覆写。初次FIGHTING CAS归属未确认时不能猜释放或复原。进程硬崩溃可能丢失补账/补释放/已知返程内存指针，须按稳定来源核对战报/行军后人工确认或释放，禁止猜测重打；不承诺整场战斗跨崩溃恰好一次。来源元数据更新时间也使用传入服务器业务时刻，不混用墙钟；旧来源在已确认事实过期且无不确定占位后可懒清理，去重不承诺永久。Bot动作全局请求号使用UUID，不依赖新实例归零的进程序号。三参数/直接记账仅诊断和测试，生产两结算均走begin/finish。联盟问候发送成功即如实计数，标记写入失败先留活进程补写再发送下一句；标记落库前硬崩溃的问候去重仍未保证。旧内存状态不能猜迁移，原验收实例与数据未切换。

这两集合是扩展新增，不改写普通玩家存档。旧内存回应标记和攻击时间不能从attackerHits、昵称或现时Bot身份猜补；实际切版须先停止旧写入者、核实导出/导入及完整备份，恢复源码也要保留新状态与查询逻辑。本轮只有自有随机库验证，未迁移原账号；没有生产数据规模或锁等待读数，不能称零停机迁移。Mongo沿现有索引入口创建受害者/发生时刻复合索引及时间清理索引，不新增后台计时器。

旧版本的内存画像/待办无法从普通玩家存档可靠推导；实际切版前须导出并登记经核实的完整画像与未完成游标，不按昵称、deviceId或UID猜身份，也不重新孵化替代旧账号。受击响应水位、24小时攻击频控及其他内存节流仍须独立验收与持久化。自有验收使用随机Mongo库和独立Redis容器，只清带当前owner标记的测试数据；共享Redis配置、原账号与IDE保持原状。

稳定原季补发邮件的送达证据随mail记录保留：BATTLE_PASS且mail.sourceRef以前缀battlePassRecovery:开头的记录，到期后隐藏/禁领但不由purgeExpired删除。tierMailWindows只在真实送达后保存。不得让旧邮件清理程序混跑删除此证据；普通邮件按既有过期清理。备份及一致切点需覆盖mail与battle_pass、activity_progress、inventory及player聚合。

普通金币及非装备道具邮件新增按邮件ID的持久附件收据后，同样不得与旧MailAppService写入者混跑。旧程序即使能读取部分收据格式，普通邮件旧领取仍不查询mail_gold/mail_item业务收据，无法据此恢复已接受组件；恢复源码版本必须保留新领取逻辑。仅金币/材料组件在新版中具备本轮断点保障，装备与其他资源旧通道仍待设计，历史claimed无收据不猜补。本轮原生/生产版本未切，旧普通邮件写者的真实混跑负控未执行。

## B24 赛季币获得与真实兑换验收

在当前 Codex 任务中运行，使用实际 `CODEX_THREAD_ID`。输入为通过验证的冻结目录：`city-runtime.jar`、完整 `config/` 及含 JAR/config SHA 的 `inputs.json`。源输入不得已有赛季部署锚点，输出必须是新目录；端口已占用会拒绝，不借已有实例。

```powershell
python -X utf8 scripts/tools/run-season-coin-purchase.py --candidate tmp/bot-match-power-projection-20261014/candidate1 --out tmp/season-coin-ui-20261014/replay-new --java-bin D:/Java/jdk/microsoft-jdk-17/bin --node D:/Java/nodejs/node20.13.0/node.exe
```

运行器复制完整输入，只在新实例配置中增加 `SEASON_START_AT`，按第一赛季配置的 SETTLE 起点派生 UTC+8 日界锚点。保持 memory、普通1×，不改变奖励/商品价格、不直接发币、不触原账号。新号先做真实主城升级，由写拦截器触发战力重算并读回赛季名次；随后三个不同 requestId 的真实结算验证只发一次。浏览器登录同 UID，通过现有 Cocos 自命中工具和真实画布点击切页/兑换，核余额、固定请求数量1/回执数量1/背包增量1，并再次点击已耗尽限购的行，核无第二单及不重复扣发。

`SEASON_SHOP_OWNER` 回执、当前线程、live PID、loopback origin/端口、memory1×/新号范围在全服结算前必须匹配。令牌由运行器临时生成，只用于本次子进程与请求，不写入回执或提交文件。回执包含冻结源码及五个依赖 SHA，运行结束核漂移与浏览器/预览/JVM/端口清理；元数据缺失或损坏不会记通过。缺必要输入或其他任务归属在任何接口前退出2，这是前提拒绝，不是兑换通过。

此验收使用明确的部署锚点夹具，未等待现实45天、未验证生产调度部署或微信原生端，不涉及真实付款。源码数量反例测试已接入 `scripts/check.sh`；它们是离线合成 count0/count2/缺发/多发等判据回归，实际链路仍以独立进程与浏览器结果为准。
<!-- B25 repeat-march acceptance -->

B25 再次出征的有限实流量具：先构建当前 Web，再使用完整冻结候选和新的证据目录运行。

```powershell
python -X utf8 scripts/tools/run-repeat-march.py --candidate tmp/bot-match-power-projection-20261014/candidate1 --out tmp/repeat-march-ui-20261014/replay-new --java-bin D:/Java/jdk/microsoft-jdk-17/bin --node D:/Java/nodejs/node20.13.0/node.exe
```

要求当前 `CODEX_THREAD_ID`，默认仅新建自有 memory 后端 9855 和预览 9856；端口已有监听或输出目录已存在即拒绝。配置与 JAR 必须匹配候选 `inputs.json`，不修改冻结文件。显式输入夹具为 dev 主城16、资源200万、时间100倍及配置武将经本实例运维邮件实际领取上阵；军队建造、训练、派遣、采集返城都走实际接口，没有军队或响应 mock。浏览器通过实际画布按钮首次派出10兵并再次派同业务参数，要求新请求号、新行军 ID，按独立训练兵种核请求/回执与完整库存扣兵返兵；同号新浏览器轮次自然无历史时，实际点击须显示理由、零派遣并且库存不变。量具核对输入 SHA 漂移及浏览器、预览、后端和端口清理。真实兵力不足战损、普通1倍时间、微信原生端和真实支付另验；本命令的通过不代表这些边界已通过。

B25 真实自动续训与手工关闭的有限实流量具，同样要求已构建 Web、当前 `CODEX_THREAD_ID` 和完整冻结候选：

```powershell
python -X utf8 scripts/tools/run-autotrain-batches.py --candidate tmp/bot-match-power-projection-20261014/candidate1 --out tmp/autotrain-ui-20261014/replay-new --java-bin D:/Java/jdk/microsoft-jdk-17/bin --node D:/Java/nodejs/node20.13.0/node.exe
```

默认仅新建自有 memory 后端9871、预览9872；明确采用主城16、时间100倍、资源200万、邮件武将输入夹具，真实兵营与训练20作前置。浏览器真实训练1建立本地输入后开既有三批预算，核全部训练成本资源和完整兵种库存；预算耗尽会停止。再次开启后在预算仍剩余时真实关闭，只完成已排批次，跨训练时长后读回不再排。费用按两个权威资源快照的 `serverNow` 独立推产出并精确核 `lastSettle`，拒绝非整量产率、变化产率或可能封顶的窗口，避免把自然产出当免费训练。响应等待提前注册拒绝处理，点击失败后仍保原始错误及清理回执。普通1倍时间、微信原生和真实战损触发补兵模式另验；不向原账号写入，也不使用事件 emit 或训练状态 mock。

B25 真实NPC损耗、缺兵再次拒绝及既有后端目标补兵模式的有限验收：

```powershell
python -X utf8 scripts/tools/run-loss-refill.py --candidate tmp/bot-match-power-projection-20261014/candidate1 --out tmp/loss-repeat-ui-20261015/replay-new --java-bin D:/Java/jdk/microsoft-jdk-17/bin --node D:/Java/nodejs/node20.13.0/node.exe
```

新证据目录、当前线程身份及完整冻结输入要求同前两份量具。默认端口9877/9878，仅新自有memory实例；显式主城16、时间100倍、初始资源5000和标准邮件武将输入夹具。20兵实际训练、实际点击现有NPC攻击、真实结算战损返城；同客户端再次出征必须明确缺兵且零派遣。独立战报摘要、详情死亡/伤兵、逐轮损耗及幸存兵力核库存。目标策略经既有HTTP指定补回原20，只补真实差额，核完整兵种、全部费用、真实队列槽/剩余时间/无科技计划时长及排队拒绝不扣费。补满后保余预算等待，不多排；既有军队页显示真实目标状态并可由玩家停止。可见Label的合成文案负控与正常实流分列，最后恢复原文。HTTP开启目标不代表玩家正目标入口已接，普通1倍时间及微信原生另验；不造对手、伤亡或业务响应。

B13 战略驻军撤防恢复（#2368）：`world_target_operation` 是按目标 ID 唯一的当前计划槽，启动与 `world_target` 热更同时校验归属及操作槽的配置锚点；不能只备份归属档而漏掉计划。需保持原 March、原集结、归属、操作槽和 dueQueue 的一致恢复输入。请求驱动全局恢复仅查询配置目录候选 ID，坏计划逐目标隔离并保留；禁止直接删 pending、换代次或把 EMPTY 当作完成证明。到家退款/删除有生产硬屏障；队列已提交但完成档失败时仍不放行。已完成召回回执不重建队列或行军，原成员当前兵权经既有 `rally-return:<marchId>` 同档回执精确返还。

本格验收使用新自有 Mongo 库和固定 JAR 的已初始化驻军夹具，真实 `/world/recall`、同请求回放、不同认证 UID 拒绝、独立 JVM 重启及 `/world/marches` 到家推进分别留证。该输入不代表真实胜战留驻，不能据此宣称 B13 完整攻占已验；正式原玩家库、原端口与账号不参与写入。

PVP 战损的兵力来源（#2369）：出征时 Army 已预扣，攻城攻方、拦截攻方和被拦截采集队的现役兵都在 March，损失仅从各自 March 扣一次；城内 Army 只接伤兵，不扣未参战余兵。真正攻城守方的兵仍在城内，按 CITY 来源先扣损再收伤兵。不能按“攻方/守方”推断兵力所在位置。四个生产战损调用点显式选择来源，既有医院容量、版本保护和 effectsStarted 后的 Uncertain 隔离继续沿用。完整发送/战斗/返程回归同时保留城内余兵、医院与幸存者守恒；这不是战略集结按成员分摊伤兵的实现，也不自动追补未知历史损失。

排行榜运行量具（#2370）：`verify-rank-runtime.mjs` 在点击前等待指定后端的同榜 GET 响应，区分预览地址与真实 HTTP 目标。根据本次响应验证空态或可见排名行，逐行核名次、名称、千分位榜值和实际纵向顺序；隐藏节点、错榜回执、失败响应、错值与旧行残留不能通过。不以新号推断全服空榜，也不删真实击杀数据来配合量具。`RANK_VERIFY_OUT` 留每榜脱敏响应/可见行证据及截图；工具单测进入 check.sh。当前该量具的横向边界仍未覆盖，非空榜图片已发现行标签锚点越界，#2371继续修并验，不将数据核对通过当作整页布局验收。

战力/榜单行布局（#2371，接上段未做）：名称与数值分别锚定行的左右边缘，固定分列并复用 capWidth，不改变通用标签或标题/提示的居中。横向门量的是实际绘制 background 自身四角，不能用被布局器撑满全屏的宿主，也不能用包含子内容的联合包围盒；逐个可见文字槽检查边界和两列间隔。自动槽盒检查与正常/长文本截图观察配合，前者不能独自证明实际字号可读。RANK_LAYOUT_STRESS_FIXTURE=1 仅在当前浏览器替换 KILL 列表响应以验长名/大数，输出显式标 fixture，后端分数不变；真实对照另跑，正常批次显式为0。#2370记录的横向越界在本格以旧产物45过3失败反证后修正，新产物真实非空/空榜与展示压力场景分别留证。

B13 多方争夺锁（#2372）：内部需要同时保护两支集结时，从 `SocialAppService.runRalliesDepartureLocked` 外层一次申报完整 ID 集合，不要在旧单集结/目标锁内追加另一方。锁顺序是国家 ID 排序去重、集结 ID 排序去重、最后目标；scope/group 改变只在释放全部旧锁后有限重试。原单方接口委托此边界并保留旧结清恢复。同 PlayerLock 身份的国家门面共享线程持有集，允许同集/子集重入而拒绝扩张；集合不能缺失或靠不存在的集结推定国家。此 API 未替代真实战斗计划、归属 CAS 或伤兵/退款幂等。

B13成员战损（#2373）：`GarrisonLossAllocation.plan(currentMembers, actualLosses)` 接受当前兵权与已按unitId展开的真实损失；`withWounded` 接受同阶总损失中的伤兵，返回逐成员伤兵/阵亡，不负责医院。`March.applyLosses` 驻军路径复用计划的survivorsByPlayer；后续冻结战果须使用同一lossesByPlayer，不得按原出兵比例另算。输入过量/无效/溢出失败不改输入，空成员身份保留；全灭后的March移除、Army收容回执与战报仍须在目标操作链集成。测试：`mvn -f server/pom.xml -pl game-core -am -Dtest=GarrisonLossAllocationTest,StrategicGarrisonTest,TierSplitTest -Dsurefire.failIfNoSpecifiedTests=false test`（先按本仓env.sh使用JDK17）。

B13单方冻结战果（#2374）：`BattleArmyFactory.freezeStrategicCasualties(march, marchVersion, rally, result, side, hospitalCapacities)`只构造不可变账务载荷，不写Army/归属。调用方须绑定原战斗输入和配置/英雄、真实医院容量与目标操作权；该方raw结果必须未预扣组医院溢出。载荷存成员原结果，构造/JSON解码验守恒而不重分；伤兵优先级用`woundUnitOrder`显式列表保存并参与相等判断，Map顺序单独不足。当前数据模型尚未接入Mongo目标操作槽，不可当旧撤防迁移或真实攻占已验。相关测试`StrategicRallyCasualtiesTest`区分真实simulate与显式账务夹具；零兵幸存结果只是计划数据，不会创建零兵March。

B13配置隔离（#2375）：`ConfigRegistry.readOnlySnapshot()`从一次volatile原始表整图读取生成独立视图，深拷贝JSON行/索引、独立global与类型缓存；不取父monitor，拒绝视图reload，rawTable诊断防御拷贝。支持正式reload的发布一致性，不支持任意并发修改原诊断JSON。战略`freezeStrategicCasualties`内部用同一视图查类型/展开损失；未来producer仍须pre-sim用同一视图构造所有战斗输入，不能把已有BattleResult自动当原配置已绑定。`ConfigRegistrySnapshotTest`含私有注册表热更、缓存/JSON隔离、守卫持锁时非阻塞读取、整图并发捕获；所有文件写入只在JUnit拥有的临时目录。

B13战略医院（#2376）：`ArmyState.admitStrategicWoundedOnce`新批次等比例收容，`StrategicWoundReceipt.policy`为PROPORTIONAL_V1；普通admitWounded原顺序不变。嵌套回执旧缺列/null及旧七参Java构造按LEGACY_ORDERED匹配原顺序并重放实际事实，不回算，不做批量回填；未知枚举拒绝读取且不改档。ArmyDocument仍同档保存整个嵌套回执，Spring Data映射新列；TestMongo兼容例必须实际unset/null并重建store验证。治疗会返兵，验证重放守恒应计入已治疗返兵，不把40+7+7误判为重复返兵。目标操作/真实战斗仍须另接，禁止用医院组件测试代替攻占验收。

B13中立余兵（#2377）：`NeutralTargetGarrisonStore`内存/Mongo双实现，同目标单文档CAS保存原始caps与实际余兵，NEUTRAL空明细和RETIRED终态均不得恢复配置兵。`StrategicTargetDefender`读取/冻结独立状态，启动与world_target重载核锚点，混合归属/有效NPC状态拒绝，Mongo模式存储守卫新增此端口。真实失败战斗写入方与目标统一恢复仍未接，不允许用fixture仓储写入当玩家争夺验收。测试只在TestMongo自有随机库写入；直接读配置原文件的夹具须向上查实际repo根，不能假设Surefire当前目录就是仓库根。旧“无owner就满兵”反证应使三兵余兵用例失败，finally逐字节恢复后必须重跑相关suite覆盖变异编译产物。

审查修复（#2377）：同一world_target同时受WorldAppService归属/操作槽与StrategicTargetDefender中立余兵约束。ConfigRegistry.addReloadGuard使用稳定具名身份：不同身份按注册顺序执行，同名实例替换，两参旧接口继续替换默认槽，任一拒绝阻止发布。两个真实服务/三类持久锚点/两注册顺序/单表与目录热更12例，旧put实现6fail。初次直接累积所有旧回调令活动历史1例在全量测试失败，已保留证据并修成具名接口兼容；补3配置层用例确认同候选/发布前顺序、后到拒绝保旧表与原异常、同名及旧接口替换不移除其他具名守卫，连活动历史共100相关全过。首次独立审查确有1个P2，按有效反证与完整回归修复，未伪称原审无问题或另行重审。

B13原输入兵数（#2378）：ArmySide.units在EnumMap防御复制后使用Collections.unmodifiableMap，保枚举迭代顺序/四兵种补零；record访问器及entry视图均不得改写已模拟原输入。BattleSimulatorTest新增真实模拟后访问器改兵/同seed复算与外部Map隔离/枚举顺序两例，旧2跑1fail，修复内核54+common62全过。此处没有修改模拟公式或零守军门槛，pre-sim战略完整输入与持久恢复仍待下一格。

B13捕获准备（#2379）：CapturedBattlePreparation.capture(happenedAt)固定单配置图，Scope.playerSide在模拟前固定原March/版本/Rally，Scope.readTarget与defenderSide把原目标定义/初始NPC及实际守军绑定该视图；simulateTarget/真实战略行军强制SIEGE，Simulation只由实际kernel私有构造。Scope.prepareHospitals固定每成员最大容量并绑定Scope，Raw组医院不截断，freezePlayerCasualties消费原输入/原版本/原名单，npcSurvivors只展开实际余兵不决定退役/击杀。调用者仍须负责真实存档版本与权责、统一闸门/地形、目标锁内占位；没有生产争夺调用或持久目标链，不可当B13闭环。HeroMapper内层HeroStats和装备账本也须用捕获view，不能只换外层；普通国策降级、医院惰性取档行为保留，新国策At口固定时点并拒绝读取失败降级。BattleRules内层Set只读。反证各把HeroStats/GearLedger改回parent均1跑1fail并精确还原；首GlobalCfg漏import及global LONG夹具写字符串错误已修，原日志保留。

#2379审查两P2修复：NationStore物化的国家副本仍携带父注册表规则，捕获口须从该副本snapshot用同一捕获view的NationRulesAssembler重建，再绑定盟主查询，最后按原事件时点惰性结算过期投票；不能用旧国策表配新通过率/槽位/最低票数，也不写回只读结算副本。普通入口继续现取规则及宽容降级，捕获At口继续传播存储异常。simulate和simulateStrategic即使两方都由playerSide直接准备、没有TargetInput，也必须核对双方原March的targetId与to坐标；同目标正例与纯NPC历史比较保留。真实Nation+内存仓储的三类热更与两入口目标ID/坐标不匹配自然RED为9跑7fail/0error/0skip，另以内存/Mongo相同快照回归确认读取不改持久事实。

B13目标操作载荷（#2380）：TargetOperationStore.Snapshot保留旧plan字段与四参构造器，另存明确kind/具体combatPlan，缺类型只对完整旧撤防档作读取归一化；读取不迁移真实数据。COMBAT保存Scope.freezeTargetBattle生成的CapturedStrategicBattle原Input/Result/目标事实/兵权版本/成员份额/NPC余兵；解码不simulate或读当前配置。单槽CAS/连续代次/同操作完整回执跨两类型一致，findByOperationId拒null/空值，未知类型或混合载荷失败关闭。WorldAppService两类都验证公共anchor，StrategicGarrisonWithdrawal遇未完成COMBAT在写March/owner/队列或推进前明确拒绝，完成COMBAT允许下一真实撤防；原全局发现/锁序/重入/逐目标隔离保留。原BSON兼容不等于旧二进制认识新战斗档，回退不能删档。COMBAT执行器、实际producer与跨文档断点恢复仍未接，不称实际攻占已验。
