package com.ironoath.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 职责：服务端启动类 —— 唯一允许出现 Spring 注解的入口。
 * 依赖：所有层（B00 分层规则：game-web 可依赖所有层）。
 *
 * <p>本层只做三件事：协议 ↔ 领域对象转换、Bean 装配、IO（HTTP / WebSocket / MongoDB）。
 * <b>玩法逻辑一律不写在这里</b> —— 它们属于 game-core / game-battle，
 * 否则就会退化成「必须启动容器才能测数值」，平衡性永远调不动（C00 公理四·五）。
 *
 * <p>注意：本工程<b>没有</b> {@code @EnableScheduling}。B00 Java 五大技术陷阱第 2 条明确禁止
 * 用 {@code @Scheduled} 扫全表做业务结算，产出统一走惰性结算，行军走 Redisson 延迟队列（B07）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
