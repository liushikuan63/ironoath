package com.ironoath.web.store;

import java.util.UUID;
import java.net.URI;
import java.net.URISyntaxException;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.ironoath.web.store.mongo.MongoIndexes;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 职责：给仓储契约测试提供一个<b>隔离的</b>真实 MongoDB 连接（随机库名 + 用完整库 drop）。
 * 依赖：MongoDB 驱动。
 *
 * <p><b>为什么不让测试直接 {@code new MongoTemplate(...)}</b>：两件容易写错的事集中在这里 ——
 * ① 库名必须随机且结束时删除，否则一次测试会把开发机上 dev/test 库的存档清空；
 * ② {@code MongoClients.create()} 是惰性的，集群不可达时它<b>不会</b>在创建时报错，
 * 会拖到第一条命令才失败 —— 所以"能不能连"必须靠真的跑一次 {@code ping} 判断，
 * 否则不可达会被记成"测试失败"而不"这条等价性检查今天没跑"（两者的处置完全不同）。
 *
 * <p>索引同样在这里确保：跑的是生产的 {@link MongoIndexes#ensure}，不是测试自己抄的一份。
 */
public final class TestMongo implements AutoCloseable {

    /** 默认打本机；CI 上没有 Mongo 时这些用例会以「跳过即未验证」的形式报出来。 */
    private static final String URI = System.getProperty("ironoath.test.mongo.uri",
            "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000");

    private final MongoClient client;
    private final MongoTemplate mongo;
    private final String database;

    private TestMongo(MongoClient client, MongoTemplate mongo, String database) {
        this.client = client;
        this.mongo = mongo;
        this.database = database;
    }

    /**
     * 尝试连接。<b>不可达时返回 null 而不是抛</b>：契约测试要把它翻译成「跳过」，
     * 而跳过必须带一句"这条承诺今天没被验证过"的理由（见各契约类的最后一条用例）。
     */
    static TestMongo tryOpen() {
        MongoClient created = null;
        try {
            created = MongoClients.create(URI);
            String database = "ironoath_contract_" + UUID.randomUUID().toString().substring(0, 8);
            MongoTemplate template = new MongoTemplate(created, database);
            template.getDb().runCommand(new Document("ping", 1));
            MongoIndexes.ensure(template);
            return new TestMongo(created, template, database);
        } catch (RuntimeException e) {
            if (created != null) {
                created.close();
            }
            return null;
        }
    }

    public static String uri() {
        return URI;
    }

    /** Build a per-test database URI while preserving the explicitly selected isolated server and options. */
    public static String uriForDatabase(String serverUri, String databaseName) {
        if (serverUri == null || databaseName == null || !databaseName.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Mongo测试URI或数据库名无效");
        }
        try {
            URI parsed = new URI(serverUri);
            String scheme = parsed.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("mongodb")
                    || scheme.equalsIgnoreCase("mongodb+srv"))
                    || parsed.getRawAuthority() == null || parsed.getRawAuthority().isBlank()
                    || (parsed.getRawPath() != null && !parsed.getRawPath().isEmpty()
                    && !parsed.getRawPath().equals("/")) || parsed.getRawFragment() != null) {
                throw new IllegalArgumentException("Mongo测试URI必须指向未指定数据库的服务器");
            }
            return scheme + "://" + parsed.getRawAuthority() + "/" + databaseName
                    + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
        } catch (URISyntaxException invalidUri) {
            throw new IllegalArgumentException("Mongo测试URI无效", invalidUri);
        }
    }

    public static String uriForDatabase(String databaseName) {
        return uriForDatabase(URI, databaseName);
    }

    MongoTemplate template() {
        return mongo;
    }

    @Override
    public void close() {
        try {
            mongo.getDb().drop();
        } catch (RuntimeException ignored) {
            // 删库失败不该让测试失败：连接已经要关了，留个随机库名也不会撞上下一次运行
        } finally {
            client.close();
        }
    }
}
