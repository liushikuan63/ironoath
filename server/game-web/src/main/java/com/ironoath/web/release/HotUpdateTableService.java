package com.ironoath.web.release;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.RawConfigTable;
import com.ironoath.web.dto.generated.ConfigTableResp;

/**
 * 职责：**白名单配置表**的「取内容」读侧（收口清单 #2287 ⑤ / V26-1）。
 * 依赖：game-config（表内容）、game-web 的 {@link ReleaseRulesAssembler}（白名单真源）。
 *
 * <p><b>为什么要有这一层，而 manifest 不够</b>：{@code POST /ops/config/manifest} 只给
 * 「**哪些表名**过期了」，不给内容，而且它是**运维端点**（要运维令牌）⇒ 客户端拿不到。
 * 客户端要真正换掉自己那份展示数据，得有一条**面向客户端**的「按表名取内容」的通道。
 *
 * <p><b>白名单是唯一的边界</b>（global.HOT_UPDATE_TABLES，表级）：白名单外的表既不进 manifest，
 * 也取不到内容 —— 否则「客户端知道表名却拿不到数据」，两边口径打架；而且那等于把全部配置表
 * （含玩法与经济数值）开成一个公开下载口，正好踩中「客户端不抄配置表」那条红线。
 *
 * <p><b>白名单外的表与不存在的表给同一个错误码</b>（{@code CONFIG_NOT_FOUND}）：
 * 区分开就等于把白名单里有什么泄露出去，而客户端本来就不该知道。
 */
@Service
public class HotUpdateTableService {

    private final ConfigRegistry configs;
    private final ReleaseRulesAssembler release;

    public HotUpdateTableService(ConfigRegistry configs, ReleaseRulesAssembler release) {
        this.configs = configs;
        this.release = release;
    }

    /**
     * 按表名取白名单表的内容（整表 JSON 字符串 + 版本号）。
     *
     * @param name 表名；必须命中白名单
     * @throws BizException 白名单外或表不存在（两者同一个码，不泄露白名单内容）
     */
    public ConfigTableResp table(String name) {
        if (name == null || name.isBlank() || !release.hotUpdateTables().contains(name)) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "没有这张配置表：" + name);
        }
        RawConfigTable table = configs.rawTable(name);
        // 整表按「与本地表同形」的方式拼：table/version/rows ⇒ 客户端解析后与它自己那张表
        // 是同一个形状，替换时不需要额外映射。
        String json = JsonUtils.toJson(Map.of(
                "table", table.name(),
                "version", table.version(),
                "rows", table.rows()));
        return new ConfigTableResp(table.name(), table.version(), json);
    }

    /**
     * 当前白名单里的表名（给「客户端该拉哪些表」那类读侧用；白名单真源只有一处）。
     */
    public List<String> allowedTables() {
        return release.hotUpdateTables();
    }
}
