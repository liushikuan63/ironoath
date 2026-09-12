# contract/ · 双端契约唯一真源

> 本目录是全项目**唯一真源**。Java DTO、TS interface、配置表 POJO 全部由
> `server/tools/config-gen` 依据本目录内容生成，**禁止手改生成物**。

## 目录

```
contract/
  proto/     协议 JSON Schema（DTO 定义）→ 生成 Java record + TS interface
  config/    配置表 JSON（由 xlsx 导出，B02 交付导出链路）→ 生成 POJO + 启动期校验
  fixtures/  战斗测试用例 JSON（B05 交付，Java 侧跑回归）
```

## 配置表 JSON 统一信封

每张表都是同一个结构，校验器与生成器按此约定工作：

```json
{
  "table": "resource",
  "version": 1,
  "comment": "人类可读的表说明",
  "rows": [ { "id": "...", "...": "..." } ]
}
```

- `table`：表名，必须与文件名（去 `.json`）一致
- `version`：单调递增整数，热更与回滚依据（B00：配置表带 version 字段）
- `rows[].id`：主键，字符串，表内唯一

## 数值书写约定（重要）

| 类别 | JSON 写法 | 载入后类型 | 理由 |
|---|---|---|---|
| 计数/等级/整数毫秒 | JSON number | `long` | 无精度问题 |
| **任何小数**（系数、比率、指数、概率） | **十进制字符串** `"1.18"` | `BigDecimal` → `long`（×10000 定点） | JSON number 是 IEEE-754 double，直接写 `1.18` 会引入二进制误差，违反 B00 铁律 5「禁止 float/double 参与结算」 |
| 定点数 | `"fixed": 11800` | `long` | 已放大 10000 倍的值，直接给整数 |

生成器与校验器会拒绝「小数字段写成 JSON number」这种写法，从源头堵住 double 渗入结算链路。

## 一致性保障

`scripts/check-contract-sync.sh` 在 CI 中重新生成一遍并与仓库内生成物 diff，
不一致即构建失败 —— 保证客户端与服务端读的是同一份契约。
