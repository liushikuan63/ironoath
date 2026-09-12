// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 红点树的一个节点（含中间节点）。父节点的 lit 由服务端聚合，客户端只消费。
 */
public record ReddotNodeView(
        String key,   // 路径式 key，分隔符与服务端 ReddotTree.SEPARATOR 一致（形如 social/help）。整棵子树的根是空串，它不下发，只作为 children 的容器。
        boolean lit,   // 此刻是否亮。叶子是注册条件本身，中间节点是「任一后代叶子亮」。既不存在的 key 也不是任何叶子的前缀 ⇒ 永远 false，这是 B12 验收 1「无假红点」的落点。
        List<ReddotNodeView> children)   // 子节点。叶子是空数组而不是省略 —— 客户端要能区分「这是叶子」与「还没下发到这里」。
{
}
