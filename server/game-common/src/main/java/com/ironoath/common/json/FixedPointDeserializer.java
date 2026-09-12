package com.ironoath.common.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.ironoath.common.num.FixedPoint;

import java.io.IOException;

/**
 * 职责：把配置表里的十进制字符串反序列化成定点 long（×10000）。
 * 依赖：jackson-databind、FixedPoint。
 *
 * <p>为什么需要它：B02 要求「配置表里不出现浮点数字段」，所以小数一律写成字符串
 * （{@code "1.18"}），而生成的 POJO 字段是定点 {@code long}（11800）。
 * Jackson 默认不会做这个转换，需要显式的反序列化器。
 *
 * <p>它同时是<b>第二道防线</b>：如果策划把小数字段写成了 JSON number（{@code 1.18}），
 * 校验器会在启动期报错；万一有人绕过校验器直接反序列化，这里也会拒绝，
 * 因为 number 分支意味着值已经过 double，精度不可信。
 *
 * <p>用法：由代码生成器自动加在定点字段上，不需要手工标注。
 */
public final class FixedPointDeserializer extends StdScalarDeserializer<Long> {

    private static final long serialVersionUID = 1L;

    public FixedPointDeserializer() {
        super(Long.class);
    }

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            String text = parser.getText();
            try {
                return FixedPoint.parse(text);
            } catch (RuntimeException e) {
                throw new IOException("定点数字段解析失败：\"" + text + "\"（" + e.getMessage() + "）", e);
            }
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            // 已经是定点整数（配置里用 xxxFixed 命名的字段走这条路）
            return parser.getLongValue();
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            throw new IOException("定点数字段不得写成 JSON 小数（会经过 double 丢精度）："
                    + parser.getText() + "。请改写成十进制字符串，例如 \"1.18\"");
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new IOException("定点数字段类型非法，期望字符串或整数，实际 token=" + token);
    }

    @Override
    public Long getNullValue(DeserializationContext context) {
        return null;
    }
}
