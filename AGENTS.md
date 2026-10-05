# 项目约定 · ironoath（代号 PROJECT_IRON_OATH，对外名《列王纪·铁誓》/ Chronicles of Kings: Iron Oath）

> 每次会话自动注入。**地图而非手册**：只放判据、红线与索引，细节在各台账与 `docs/`。
> 与 `~/.dsh/AGENTS.md`（全局纪律）冲突时以全局为准；本文件只**加严**或细化，不放宽任何一条。

## 一、这是什么

微信小游戏 SLG（中世纪城堡内城 + 大地图策略）。
服务端 `server/`（Java 17 / Spring Boot / 多模块 Maven：common / config / core / battle / web）；
客户端 `client/`（Cocos Creator 3.8.7 + TypeScript）；
双端契约的唯一真源是 `contract/proto/*.schema.json` 与 `contract/config/*.json`。
> **标识口径的唯一真源在 `README.md` 顶部**：中文《列王纪·铁誓》 / 英文 Chronicles of Kings: Iron Oath / 代号 PROJECT_IRON_OATH / 仓库与包标识 ironoath（GitHub 仓库同名）。Java 包名 `com.ironoath` 暂不改——另立一格，改包名必须**同批改门禁谓词**（97 处引用里写死了 `com/ironoath` 路径，不同批改就会扫空目录假绿）。

## 二、验证入口（改完必须现跑，不许引用旧读数）

| 目的 | 命令 |
|---|---|
| 静态门（41 道）+ 客户端单测 | `bash scripts/check.sh` |
| 服务端 JUnit + 客户端单测 | `bash scripts/test.sh`（等价 `mvn -f server/pom.xml test`） |
| 全量构建（含契约生成） | `bash scripts/build.sh` |
| 契约/配置表重生成（改 `contract/` 后必跑） | `npm run gen` |
| 前台产物 | `bash scripts/build-webmobile.sh` |
| 运行时探针（要产物 + 活后端） | **首选** `bash scripts/run-batch-dual-backend.sh`（自动起**两台**后端：普通 + dev 提速档）；单份 `node tools/verify-*.mjs` |
| 同上（只想要单后端 / 手工分批） | `bash scripts/run-runtime-probes.sh`（⚠️ 不传 `BOOST_BACKEND` 时 `verify-nation-live` 会报前提不足退 2） |
| 客户端发送口缺口现数 | `node tools/report-client-send-paths.mjs`（永远退 0） |

**「全绿」的判据**：同一轮跑完 `check.sh` + `test.sh`（或 `mvn test`）+ headless 构建 + 真启动/探针，
并记录各条退出码与读数。**只跑单测不能说玩家闭环完成。**

运行时探针的**后端变量名不统一**（`BACKEND_ORIGIN` / `MARCH_BACKEND` / `SOCIAL_CREATE_BACKEND` /
`ART_VERIFY_BACKEND` / `EQUIP_BACKEND` …）：传错会**静默回退到 8080**，跑出一片假红 —— 先读每份探针首行的
「后端 http://…」再读结果。

**探针批跑不是可选项**（2026-09-30 一笔实证）：改了**共用件**（`PermissionGates`、`PanelNav`、`Store`、
社交页的行池 / 行序、聊天页签的绘制路径）之后，**只跑自己碰的那两份不够** ——
那一轮单跑全绿、批跑红 3 份，其中 1 份是本会话真引入的（给联盟页加了一行入口，
把「踢出/设职」挤到第 2 页，而探针只读**画出来的行**、它不翻页），另 2 份是环境造成的假红。
做法：`BACKEND=http://localhost:8080 RUNTIME_PROBES_TIMEOUT=240 bash scripts/run-runtime-probes.sh <清单文件>`
挑与改动相关的十份左右跑；**加新入口 / 改行序 / 改门禁依据**这三类改动，必须带上
「已有内容可见性」那一族（`verify-social-permission-runtime` · `verify-social-create-runtime` · `verify-rank-runtime`）。

**dev 提速档会改变量具前提**（同上那笔）：`IRONOATH_DEV_CITY_LEVEL=16` 让新号落 16 级，
于是「新号 1 级、两道门都关着」那类探针（`verify-social-create-runtime` 的 A 相）会假红 5 条。
那类探针已加**显式守卫**（读到等级 > 1 就退 2 并说明"这一份要跑在不带提速档的后端上"）——
**看到退 2 不是功能坏了，是量具没架对**；国家正链路要的正好是**开着**档的后端（`verify-nation-live.mjs`）。

## 三、本机环境（踩过的坑，别再重新探索）

- **bash 必须走 Git Bash**：`C:\Program Files\Git\bin\bash.exe`。PATH 里的 `bash` 是 WSL 的
  `C:\WINDOWS\system32\bash.exe`，本机 WSL 无 `/bin/bash`，直接跑报 `execvpe(/bin/bash) failed`。
- **客户端单测要 Node 20**：`export PATH="/d/Java/nodejs/node20.13.0:$PATH"` 后再跑门；
  默认 `node` 是 v24，`node --test <目录>` 语义变了（症状像"用例坏了"，实际一条都没跑）。
- **Cocos 命令行构建要先清 `ELECTRON_RUN_AS_NODE`**：本机会话若注入了它，`CocosCreator.exe` 会以 node 模式启动
  并报 `bad option: --project`（`--help` 打出 node 的 help 就是这条的症状）。
  跑法：`env -u ELECTRON_RUN_AS_NODE bash scripts/build-webmobile.sh`。
- **Java 测试硬连本机 mongod** `127.0.0.1:27017`（**本机没有原生 mongod 服务** ——
  2026-10-05 实测：无 `mongod` 进程、无 `MongoDB` 服务项、无 `C:/D: Program Files/MongoDB` 安装目录。
  ⇒ **用容器起**，镜像本机已有（`docker.m.daocloud.io/library/mongo:7`），数据落 **D 盘**：
  ```bash
  docker run -d --name mongo27 -p 27017:27017 \
    -v "D:\mongodb-data:/data/db" --restart unless-stopped \
    docker.m.daocloud.io/library/mongo:7
  ```
  无需认证（`TestMongo.java` 的连接串就是 `mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000`）；
  `--restart unless-stopped` ⇒ 重启后自动起来。
  ⚠️ **没起它就跑 `scripts/test.sh` 会红**，且不是功能红：
  `InventoryEquipEquivalenceTest` 会抛
  「本机 MongoDB 没接通……起一个 27017 或按 TestMongo 的说明连库再跑」，
  并**连带跳过 199 条**（实测 `1200 跑 / 1 红 / 199 跳` ⇒ 起容器后 `1200 跑 / 0 红 / 0 跳`）；
  `mvn` 默认指向 JDK 8，`scripts/env.sh` 会自动覆盖成 JDK 17。
- **构建与量具不能并行**：`build-webmobile.sh` 中途会清空产物目录，正在加载页面的探针会报 `ENOENT index.html`。

## 四、三条防腐纪律（本仓最贵的教训）

1. **文档里的计数与状态，引用前必须现跑**（表行数、端点是否存在、调用点数）。
   「文档说 A、代码是 B」时**先信现跑结果**，再把文档改对 —— 本仓多次出现"文档写着缺口、代码早已实现"。
2. **「判定写了没接上」是头号缺陷形状**：实现完用**生产调用点计数**复核
   （`grep -rn "\.方法名(" server --include=*.java | grep -v test`、`report-client-send-paths.mjs`），
   而不是"类里有这个方法"。
3. **待裁决不发明**：找不到出处的数字与规则，写进台账（`收口清单.md` §五/§七、批次文件 §五），不替产品决定。

## 五、红线（比全局纪律更严的部分）

- **客户端不抄配置表**：数值与中文名一律来自服务端下发；客户端只有生成的类型，没有表数据。
- **不许把内部 id / 枚举原文印给玩家**（`pool_std`、`hero_guanyu`、`OFFICER`、`main_city Lv1` 这一族
  已出过多次事故，横扫有「屏上不出现裸 id」一维）。查不到名字就给「未知武将」这类回退语。
- **生成物不许手改**：`client/assets/scripts/net/generated/**`、`server/**/dto/generated/**`、
  `**/config/cfg/**` 由 `npm run gen` 产出，改了契约就重跑生成器。
- **服务端禁常驻定时器**（`check-no-scheduled.sh` 是门禁）：时间推进一律惰性驱动。
- **新增 `.ts` 要连 `.ts.meta` 一起提交**（仓库里 187 个 `.ts.meta` 都是入库资产），
  新增测试类先 `git add`：⚠️ **不是为了让 `check-dangling-test-refs.sh` 去判红，
  而是因为它扫不到未跟踪的文件**。实测（2026-10-05 自测）：`scripts/check-dangling-test-refs.js`
  的 `L69 !SELF.has(p)` + `L80 scan(tracked())` ⇒ **只扫 `git` 已跟踪的文件**。
  对照读数：同一个悬空引用，**未跟踪时 `EXIT=0`（正确放过）／`git add` 进索引后 `EXIT=1`
  （被抓）／还原后 `EXIT=0`**。⇒ 该门**自带 `--self-test`** 且通过 ⇒ **门本身是好的，只是前提没写清**。
- **交付前不夹带无关文件**：多会话并行时先分辨改动归属，只提交明确的路径。

## 六、地图（要什么去哪读）

| 要什么 | 去哪 |
|---|---|
| 设计公理 / 数值 / 合规红线 | `B00_共享上下文.md`、`C00`~`C08`、`B01`~`B25` |
| 待修清单（唯一活得过会话的载体） | `收口清单.md`（**只增不删**、一行一条） |
| 上线前必做 | `上线检查清单.md` |
| 逐条验收证据 | `验收矩阵.md` |
| 下一步做什么 | `CC开发全流程.md` §8；`待完善收口_VibeCoding开发包.md`（V10~V16 任务卡） |
| 跨会话接续 | `.qoder-work-queue.md`（**未跟踪**；第一条未完成项就是下一件事） |
| 客户端够不着的发送口 | `客户端发送口缺口清单.md` + `report-client-send-paths.mjs` |
| 工程操作（怎么跑起来） | `DEVELOPMENT.md`、`Cocos调试落地清单.md` |

## 七、台账纪律（`收口清单.md` 单列，因为它最容易改坏）

- 一行一条、**不带裸竖线**（`||` 与 `a | b` 会被形状门判红）；改之前先备份；
- `git diff --numstat -- 收口清单.md` 的**删除数必须为 0**；
- 插完跑 `bash scripts/check-checklist-table.sh`；编号先让后取；
- 更正旧结论用**就地补注**（划掉 + 写真值 + 保留原始理由），不删条目。

## 八、留档纪律（2026-10-04 用户定，每轮都适用）

**任务要持续推进；且无论"完成一格"还是"发现新问题 / 新想法"，都必须落盘成
vibiecoding 文档记录 + 一次 git 提交 + 推送远端。**

- **落档位置**：`待完善收口_VibeCoding开发包.md` **§四「实施记录」**（那张「格 | 提交 | 验证读数 |
  截图/证据 | 未做」的表）——**完成或发现都往这里追加，不攒到收尾**。
  跨会话接续另写 `.qoder-work-queue.md`（未跟踪，只放指针与判据，不放长文）。
- **每行的「未做」列必须照实写**：遗留的真隐患、真根因还待下一轮读数、某条路为何走不通——
  这三样正是下一格不用重走的依据。
- **提交要原子**（一格一次），message 写「判据 + 反证 + 坑」，不写"做了调整"。
- **推送不再需要逐次等拍板**：本仓的 push 已由用户常设授权，
  前提是①改动归属分辨清楚（只提本会话的）②当轮门禁有读数（`check.sh` 或该格对应的判据）。
  **推之前先 `git fetch` 看落后数**（本仓惯例是快进推送，落后不为 0 先同步上游再推）。
- 落档纪律的**反面**：只在对话里说"我发现了 X"而不进 vibiecoding 文档 —— 下一轮会话看不到，
  等于没记录；只改代码不写「未做」列 —— 下一格会以为那半已经验过了。
