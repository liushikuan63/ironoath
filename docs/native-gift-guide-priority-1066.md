# 原生礼包与引导层级修复候选 #1066

来源：F26原生真实账号教程第3步中，正常重开后限时礼包弹出，截图 D:/tmp/ironoath-wechat-20261010/root-native-onboarding-v26/stage3-retry1-stop-state.png 显示引导气泡覆盖礼包内容。

方案：礼包显示时通过实际可见性回调收起完整引导层，礼包自己的全屏遮罩继续保护输入；礼包关闭或服务端不弹时重画服务端当前步骤。保留面板资源教学的独立气泡抑制，不上报 COMPLETE/SKIP，不改礼包触发规则或付费流程。

实现：GiftPopupView.attach/hide 发出 onVisibilityChanged；GameBootstrap.targets 连接 GuideView.setOverlaySuppressed。GuideView 使用弹层集合，暂时 clearLayer，保留 driver/suppressedPanels；repaint 和异步HELD回调也遵守抑制；销毁时清理集合。

验证：实际原源39用例36通过/3失败/0跳，修复源39/39/0失败0跳，完整客户端1232/1232/0失败0跳，headless/testcheck 类型检查实际0，git diff --check0。证据为 D:/tmp/ironoath-wechat-20261010/gift-guide-1066-wiring-before.log、wiring-after.log、client-full.log 与独立 actual-exit 文件。节点桩执行真实 attach/hide 及真实targets回调，证明接线与进度恢复；不证明原生像素。

未做：候选尚未进入主源F26；Cocos产物构建、真实礼包打开/关闭像素与输入回归、共用GuideView相关整批及完整新冻结门禁待执行。主源原生资源与后续七步仍在旧冻结输入上运行，不能将候选单测计入F26总验收。


#1067 离线汇总全局层级补齐

真实失败：F26原生恢复后离线汇总位于礼包上层；只读遮挡诊断 safeHitPoint false 显示知道了按钮被 GuideMask 覆盖。官方横屏API返回成功但画布仍390x844且安全区变化，不能用返回值证明横屏。旧项目正常close0和普通9488后端cleanup0/pidGone/portClosed后才允许主源改动。

候选：OfflineReportOverlay.render/hide 真实可见性同样接入 GuideView 全局弹层集合。空汇总调用hide，多个弹层各自关闭后才恢复当前服务端步骤。不改变资源收益、展示指纹、跳转、教程推进或赠礼规则。

回归：候选补齐前40用例39通过1失败；补齐后40通过0失败0跳；完整客户端1233通过0失败0跳；headless/testcheck各实际0。新增用例执行真实 OfflineReportOverlay.render/hide 和生产GameBootstrap回调，覆盖汇总关闭、空汇总及礼包仍显示的嵌套次序。

未做：候选尚未构建/原生实画。合入主源后需要新冻结全工程、相关整批、真实礼包/离线汇总开关与未推进教程，模拟器方向通过真实控件核实，余原生流程和内存根因仍待。
