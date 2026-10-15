package com.recsys.streaming;

import com.recsys.persistence.MybatisSettings;
import org.apache.flink.configuration.Configuration;

import java.nio.file.Path;
import java.sql.Driver;
import java.util.ServiceLoader;

/** 仅依赖最终fat jar的子JVM探针；不用测试框架或Maven依赖classpath替打包产物补类。 */
public final class CategoryEnricherArtifactProbe {
    private CategoryEnricherArtifactProbe() {
    }

    /** 运行真实类目查询，同时断言运行类来源、Mapper资源、驱动SPI和无Spring启动依赖。 */
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[3]).toRealPath();
        for (Class<?> type : new Class<?>[]{RealtimeFeatureJob.class, MybatisSettings.class}) {
            var source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(jar.equals(source), "production class must come from shaded artifact");
        }
        require(RealtimeFeatureJob.class.getClassLoader().getResource("mapper/streaming/ItemCategoryMapper.xml") != null,
                "missing Mapper XML");
        require(ServiceLoader.load(Driver.class).stream().anyMatch(p -> p.type().getName().equals("org.postgresql.Driver")),
                "missing PostgreSQL driver SPI");
        try {
            Class.forName("org.springframework.context.ApplicationContext");
            throw new AssertionError("unexpected Spring runtime dependency");
        } catch (ClassNotFoundException expected) {
            // Flink通过MP核心直接运行，不能依赖Spring上下文补齐依赖。
        }
        Class.forName("org.apache.flink.connector.base.source.reader.SourceReaderBase");
        var enricher = new RealtimeFeatureJob.CategoryEnricher(args[0], args[1], args[2]);
        enricher.open(new Configuration());
        require("artifact-类目".equals(enricher.map("{\"itemId\":9007199254740993}").category), "artifact query failed");
        require("null-id".equals(enricher.map("{\"itemId\":0}").category), "artifact null-id mapping failed");
        System.out.println("STREAMING_ARTIFACT_QUERY_PASS");
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
