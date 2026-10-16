# Bot 单次观察带兵上限快照实施计划

> Agentic workers: use superpowers:executing-plans inline; finish with one fresh independent whole-change reviewer. No implementation delegation.

**Goal:** 同一次 Bot 观察的人口与训练建议使用一次成功读取的带兵上限；下一次观察和真正训练继续读取当前值。

**Architecture:** HeroAppService 产生仅含玩家身份与 long 上限的不可变 TroopCapSnapshot（构造器私有），由 BotWorldAdapter 的现有单次观察局部变量持有。ArmyAppService 新增仅用于建议查询的快照重载，在原 CitySnapshot 回调内复用值；旧入口与实际训练不接收快照、不变更读法。没有服务字段、全局或 ThreadLocal 缓存。

**Tech Stack:** Java17 / Spring Boot3.2.5 / 项目锁定依赖；不增加依赖。

**Spec:** 用户已授权持续 Linux5000 预算队列；#1412/#1413 实际 Mongo 计数与 #1414 成本代理，见四台账与 tmp/persistent-budget-resume-20261015 的绑定证据。

## 依据与约束

现有观察先调用 heroes.troopCap，然后 firstTrainable 的原城建回调再次调用。已选150观察中 Hero.find350（含冷创建的额外读取）和 Inventory.find300；计数本身不能分解每条链耗时。这里不改变两个城建加载/结算/玩家保存/城建版本保存，不改变 Army loadOrCreate、Quest/Gold 恢复或事件顺序，不合并观察与执行，不改实际训练校验、RNG、调度及锁协议。

一次观察本来是建议事实：快照使人口与训练建议采用同一成功上限；它不是后续动作授权。持锁丢失后的 fencing 或全局实时一致性不由此提供，真正训练仍重新读取并校验。快照只供当前观察使用；异常不会产生快照，0 为有效值。跨玩家快照在任何城建/军队 I/O 前拒绝。

固定验收原195/fbe/606、2e1 runner、5000/30000ms/P99<5ms/180s/业务时钟/4workers/Mongo Netty2/46cfg/AOFalways 不变。原9490Java21756、微信25724、P67原DB1x不动；不push部署付款新前台。Windows限制、不完整JFR、未证Netty异常保留。单对照不能称稳定提速。

## Review focus

- 异常发生位置保持：首次装备/武将加载失败在 Quest/训练建议之前传播；没有失败结果复用。
- 下一观察、另一玩家、普通 troopCap 与普通 firstTrainable 都现读；成功0不能误当未读取。
- Army 冷创建与两个城建结算/保存仍实际执行；真实训练不使用旧建议快照。
- 旧测试只允许必要的调用签名迁移，数值/事件顺序/失败状态断言保留。
- 有限建议快照不能冒称持续持锁、跨实例事务、真实5000达标或原生验收。

## Task 1 — 唯一实现与审查单元

Paths: production HeroAppService.java, ArmyAppService.java, BotWorldAdapter.java; new tests bot/BotObservationTroopCapSnapshotTest.java and service/ArmyTroopCapSnapshotTest.java; this plan and four mandatory ledgers. Additional necessary tests must be explicitly frozen.

- [x] 新增使用现有 API 的实际 Bot 观察回归，先跑出有效断言首红，归档输入/XML/退出码。
- [x] 最小实现私有构造不可变玩家快照与建议重载，普通入口和训练保持原读法；加玩家隔离/0/新观察/初始化/异常/训练现读等回归。
- [x] 定向变异证实关键断言；全量 build/check/test 不跳过并逐XML核数，冻结源码和候选JAR差异/46cfg，真实Linux新memory+旧新persistent与同计数诊断；相关后端探针/Web原产物完整性。失败原文保留，不以重试抹去首红。
- [ ] 四docs如实记录完成与未做，冻结完整包交fresh reviewer；通过后本地原子提交并独立blob读回，不push，继续原预算队列。

冻结检查点：前三步验证完成，最后的独立终审与提交仍待实际批准；绑定 approval/commit receipt 才能替代该待办。
