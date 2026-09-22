package com.ironoath.web.store.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

/**
 * 职责：钉住存档投影口那条 Mongo 查询的<b>形状</b> —— 过滤面与字段投影列集合。
 * 依赖：无（纯查询对象断言，不需要本机 MongoDB）。
 *
 * <p><b>为什么要单独钉这个</b>：投影口省的是字节与反序列化，而往返计数看不见字节。
 * 有人把 {@code fields().include(...)} 整条删掉、或者给读模型加一列却忘了同步投影列表，
 * 结果集仍然一字不差 —— 两版等价测试当场全绿，生产上那一列却恒为 0（或整档又开始白搬）。
 * 能失败的唯一去处就是这条查询本身。
 *
 * <p><b>两侧一起钉</b>：投影列必须与 {@link PlayerBriefDocument} 声明的属性一一对应，
 * 少一侧都是缺陷 —— 少在投影侧是白搬整档，少在模型侧是那一列读不出来。
 */
class PlayerBriefProjectionQueryTest {

    private static final Set<String> IDS = new LinkedHashSet<>(List.of("P-甲", "P-乙"));

    @Test
    @DisplayName("字段投影恰好覆盖读模型声明的列：删掉投影＝白搬整档，漏一列＝生产那列恒为 0")
    void theQueryProjectsExactlyWhatTheReadModelDeclares() {
        Query query = MongoPlayerStore.briefQuery(IDS);
        Document fields = query.getFieldsObject();

        assertThat(fields)
                .as("投影整条被删掉时结果集仍然一字不差，只有这里能红")
                .isNotEmpty();
        Set<String> declared = Stream.of(PlayerBriefDocument.class.getRecordComponents())
                .map(component -> component.getName())
                // 主键由驱动默认带回，不写进 include 列表
                .filter(name -> !"playerId".equals(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(fields.keySet())
                .as("投影列集合必须与读模型的属性一一对应：加列要连投影列表一起改")
                .isEqualTo(declared);
        assertThat(fields.values())
                .as("只能是包含式投影（值为 1），排除式投影会把 _id 之外的语义整个改掉")
                .containsOnly(1);
    }

    @Test
    @DisplayName("过滤面是一条 _id $in 且只含被点名的人：去掉它等于把全服档案搬进一次列表响应")
    void theFilterIsASingleInOnTheNamedIds() {
        Document filter = MongoPlayerStore.briefQuery(IDS).getQueryObject();

        assertThat(filter.keySet()).as("这条读法只该有一个条件").containsExactly("_id");
        Document in = filter.get("_id", Document.class);
        assertThat(in.keySet()).as("$in 而不是逐 id 的 $or，也不许夹别的条件").containsExactly("$in");
        // 集合相等而不是逐项断言：$in 的值是驱动原样保存的那个 Set，泛型在 fluent 链上只会添乱
        assertThat(new java.util.HashSet<>((java.util.Collection<?>) in.get("$in")))
                .as("被点名的 id 原样进 $in，一个不多一个不少")
                .isEqualTo(new java.util.HashSet<>(IDS));
    }
}
