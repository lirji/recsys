package com.recsys.advertiser.catalog;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

/** 可靠profile只允许追加治理路由和显式writer-id，不改变业务分片、连接模板和SINGLE语义。 */
class CatalogShardingProfileTest {
    @Test @SuppressWarnings("unchecked")
    void reliableProfilePreservesEveryLegacyRuleExceptExplicitCatalogAdditions() throws Exception {
        Map<String,Object> legacy=load("/sharding.yaml"),reliable=load("/sharding-catalog-reliable.yaml");
        var rules=(List<Map<String,Object>>)reliable.get("rules");
        var sharding=rules.get(0);var tables=(Map<String,Object>)sharding.get("tables");
        var expected=Set.of("advertiser_catalog_schema_history","catalog_shard_control","catalog_advertiser_guard",
                "catalog_identity","catalog_aggregate","catalog_command","catalog_step","catalog_outbox","catalog_task_item");
        assertThat(tables.keySet().stream().filter(n->n.startsWith("catalog_") || n.equals("advertiser_catalog_schema_history")).toList()).containsExactlyInAnyOrderElementsOf(expected);
        for(String name:expected) {
            var table=(Map<String,Object>)tables.remove(name);
            assertThat(table.get("actualDataNodes")).isEqualTo("ds_${0..1}."+name);
            assertThat(table.get("databaseStrategy")).isEqualTo(Map.of("standard",Map.of("shardingColumn","shard_id","shardingAlgorithmName","catalog_shard_inline")));
            if(name.equals("catalog_identity"))assertThat(table.get("keyGenerateStrategy")).isEqualTo(Map.of("column","entity_id","keyGeneratorName","snowflake"));
            else assertThat(table).doesNotContainKey("keyGenerateStrategy");
        }
        var algorithms=(Map<String,Object>)sharding.get("shardingAlgorithms");
        assertThat(algorithms.remove("catalog_shard_inline")).isEqualTo(Map.of("type","INLINE","props",Map.of("algorithm-expression","ds_${shard_id}")));
        var snowflake=(Map<String,Object>)((Map<String,Object>)sharding.get("keyGenerators")).get("snowflake");
        var props=(Map<String,Object>)snowflake.get("props");
        assertThat(props.put("worker-id",1)).isEqualTo("$${CATALOG_WRITER_ID::0}");
        // 仅可靠profile关闭业务参数日志；旧profile保持其原值。
        var runtime=(Map<String,Object>)reliable.get("props");
        assertThat(runtime.put("sql-show",true)).isEqualTo(false);
        assertThat(reliable).isEqualTo(legacy);
    }
    private static Map<String,Object> load(String resource)throws Exception {
        try(var in=CatalogShardingProfileTest.class.getResourceAsStream(resource)) {
            String yaml=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("!SHARDING","").replace("!SINGLE","");
            return new Yaml().load(yaml);
        }
    }
}
