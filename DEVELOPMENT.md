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
   `tools/verify-nation-policy-ui.mjs:363` 读的是**自己进程的环境变量**，不是后端的 ——
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
