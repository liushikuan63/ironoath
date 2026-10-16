# Bot 单次观察既有军队快照实施计划

> Agentic workers: execute inline with superpowers:executing-plans; one fresh-context whole-change reviewer at the end. No implementation delegation.

**Goal:** 同次观察的人口与训练建议复用首次已存在的军队快照；未读到军队仍走原创建/读取路径，实际训练始终现读。

**Architecture:** ArmyAppService 产生玩家绑定、私有构造的只读 ArmyObservationSnapshot，内部持有领域 ArmyState.Snapshot。BotWorldAdapter 在原首次 Army 读取位置取得局部对象；新增四参 firstTrainable 建议重载使用领域快照重建临时 ArmyState。现有两参/三参建议入口保持 loadOrCreate，真实 train 不接受快照；没有服务字段、ThreadLocal、全局或跨观察缓存。

**Spec:** 持续预算队列 #1415~#1421；同listener selected150observe Army.find350，生产链是直接人口读取加 firstTrainable 内再次读取（冷创建另有额外查询）。新目标只减少已存在记录的第二次读取，预期selected warm部分100次，不预先把预期当真实读数。

**Tech Stack:** Java17/Spring Boot3.2.5/现有 JUnit 与真实业务 fixture；不增加依赖或修改协议/配置。

**Evidence-driven amendment:** 扩展Web回归16/1暴露领域Snapshot的Map仍关联原Army；随后独立领域回归4/3有效首红。该领域接口本就声明不可变，统一在Snapshot构造器复制troops/queue/wounded/treatCost，并保留null形状、LinkedHashMap顺序及已有autoTrain/rallyRefunds处理；观察层依赖统一契约，不再保留额外私有复制绕行。不是schema或业务数值变化。

## Constraints and rulings

- 保留两个 City 结算/保存、Army/Hero冷初始化、Quest/Gold/事件顺序、锁/owner读、RNG、实际扣费及训练校验。
- **Ruling:** 首次未读到 Army 时不缓存空态，不自行跳查直接 insert。四参入口依原 loadOrCreate 重新查/原子创建/重读，保留迟到创建及原异常位置，代价是冷阶段不减少查询。
- **Ruling:** 已存在 Army 是当前观察的建议快照；即使测试注入在后续读取间写了 Army，人口与训练建议也采用同一份。它不是实际动作授权或持续持锁/fencing；普通建议与实际训练重新校验。
- 原195/2e1/fbe/606、5000/整轮小于30000ms/P99小于5ms/180s、businessNow、4workers/Netty2/46cfg/真实Mongo+Redisson+RedisAOFalways不改。两组WSL2挂载诊断均失败保留，不将DockerDesktop当独立Linux。正式入口待异步澄清，期间只做已授权本地工作。
- 不push、生产部署、付款、新前台；原9490Java21756/微信25724/P67原DB1x不动。JFR2601不完整/Windows/未证Netty旧边界保留。

## Review focus

玩家隔离与任意 City/Army I/O 前拒绝错误快照；存在但零兵力不可当作缺失；不存在时迟到创建必须可见；每次观察/普通API/真实训练现读；完整快照含训练队列/额外槽位等不得抄第二份领域公式。只读快照不可向外泄漏可变 ArmyState。

## Task 1 — one independently reviewable unit

Production: core/army/ArmyState.java, web/service/ArmyAppService.java and web/bot/BotWorldAdapter.java. Tests: new core/army/ArmySnapshotImmutabilityTest.java, bot/BotObservationArmyReadSnapshotTest.java and service/ArmyObservationSnapshotTest.java. Plan + mandatory four ledgers accompany the exact11 owned paths.

- [x] 先新增使用当前API的真实Bot回归：已有军队每观察一次查询、下一次现读、失败重试、冷初始化与迟到创建、建议一致性/真实训练现读。有效断言首红归档后才改生产。
- [x] 最小实现，补玩家隔离、不可变快照及普通入口/缺失分支控制；有效定向变异并恢复源码、聚焦全绿。
- [x] 同源全量build/check/test与原始XML核数；候选JAR/46cfg/差异证明；固定标准Linux memory和旧新持久化真实5000及选中Mongo计数，相关真实后端路径按影响验收。不以WSL或预算失败冒充正式Linux通过。
- [ ] 四docs记录真实结果及未做，freeze whole-change fresh review，批准后local原子提交和blob读回；继续原队列，不push。

冻结检查点：前三步已验，独立whole11path审与提交仍待真正批准/receipt；整体5000目标不因本格完成而自动满足。
