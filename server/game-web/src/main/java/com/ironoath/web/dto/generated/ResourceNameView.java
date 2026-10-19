// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一种资源的中文名。**为什么必须下发**：资源名住在 resource 表的 name 列，而**客户端没有配置表加载器**（表不进包，客户端只有生成的类型、没有表数据）⇒ 客户端拿不到表。抄一份的后果是**改表只改一半**（服务端认了新名，客户端还显示旧名），而两边各自自洽 ⇒ 单测 / 类型检查 / 构建全绿，没有一条断言会红。**一次下发全部资源名**（而不是每个金额对象各带一个 name）是有意的：面板上显示资源名的地方有十几处，其中好几处手里**只有类型没有金额对象**（抽卡消耗、社交政策消耗、地图实体、战报指标）—— 逐个对象带 name 就要改五份契约，而一次下发让客户端自己建映射，十八处调用点一行不用改。
 */
public record ResourceNameView(
        ResourceType type,
        String name)   // 资源中文名，来自 resource 表的 name 列。客户端不得自行翻译
{
}
