// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一步的展示参数。全部字段都来自 `guide.json` 的同一行 —— 客户端不拼文案、不填坐标、不猜顺序。
 */
public record GuideStepView(
        String id,   // 步骤 id。上报进度时原样回传（服务端按它查当前步，不认下标）。
        String name,   // 步骤名（给埋点与客服看的短名；玩家看到的是 text）。
        long stepIndex,   // 序号。**顺序的唯一来源是表**，下发序号只为让客户端能显示「第 3/7 步」这种进度而不必自己数。
        GuideTrigger trigger,   // 触发时机。
        String panelKey,   // 要打开/已在的面板 key（`PanelNav` 的那套：city / quest / army / targets / hero / social）。可空：`STATE_REACHED` 那一步不需要具体面板。
        String highlightPath,   // 高亮定位（按面板 key 体系的节点路径）。可空表示只提示不指东西 —— 挖洞遮罩没有高亮目标时就是整屏暗。
        String maskArea,   // 遮罩范围：`full` 或 `x,y,w,h`。本批 7 步全是 `full`（B18 验收 6 要的是「遮罩吃触摸」，与几何无关），而格式留着是因为机制上确实需要矩形那一种。
        String text,   // 气泡文案。**只在表里存一份**：客户端硬编码一句就会让「改文案要发包」变成事实（B12 禁止项）。
        boolean skippable)   // 能不能跳。`false` 时界面不显示跳过按钮，硬发 SKIP 会被拒（`GUIDE_STEP_NOT_SKIPPABLE`）。
{
}
