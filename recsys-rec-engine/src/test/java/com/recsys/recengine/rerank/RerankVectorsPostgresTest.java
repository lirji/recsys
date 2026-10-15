package com.recsys.recengine.rerank;

import com.recsys.common.dto.RecommendItem;
import com.recsys.content.Item;
import com.recsys.recengine.persistence.mapper.derived.EngineEmbeddingMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 真实pgvector保护MMR/DPP的输出顺序、元数据与无向量降级；迁移不调整算法参数。 */
@Testcontainers
class RerankVectorsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static EngineEmbeddingMapper mapper;
    private static final List<RerankCandidate> CANDIDATES=List.of(
            new RerankCandidate(1,1),new RerankCandidate(2,.99),new RerankCandidate(3,.98),new RerankCandidate(4,.1));
    @BeforeAll static void schema() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        jdbc.execute("CREATE EXTENSION vector");
        jdbc.execute("CREATE TABLE item_embedding(item_id bigint PRIMARY KEY,embedding vector(3))");
        jdbc.execute("COMMENT ON TABLE item_embedding IS '重排向量迁移隔离测试表'");
        jdbc.execute("COMMENT ON COLUMN item_embedding.item_id IS '候选物料标识'");
        jdbc.execute("COMMENT ON COLUMN item_embedding.embedding IS '测试三维向量'");
        mapper=new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(),"classpath*:mapper/recengine/derived/**/*.xml"))
                .getMapper(EngineEmbeddingMapper.class);
    }
    @BeforeEach void reset() { jdbc.execute("TRUNCATE item_embedding"); }
    private List<Reranker> rerankers() { return List.of(new MmrReranker(mapper),new DppReranker(mapper)); }
    private RerankInput input(int size,int pool,String thirdCategory) {
        var items=Map.of(1L,item(1,"A"),2L,item(2,"A"),3L,item(3,thirdCategory),4L,item(4,"C"));
        return new RerankInput(size,Map.of(1L,List.of("VECTOR","HOT"),3L,List.of("TAG")),items,
                Map.of("lambda","0.2","dppAlpha","1","poolSize",String.valueOf(pool)));
    }
    private static Item item(long id,String category) { return new Item(id,"test",category,List.of(),"",0); }
    @Test void actualVectorsDiversifyWithinTheSameCategoryAndKeepMetadata() {
        jdbc.execute("INSERT INTO item_embedding VALUES(1,'[1,0,0]'),(2,'[1,0,0]'),(3,'[0,1,0]'),(4,'[0,0,1]')");
        for(var reranker:rerankers()) {
            var out=reranker.rerank(CANDIDATES,input(2,200,"A"));
            assertThat(out).extracting(RecommendItem::itemId).containsExactly(1L,3L);
            assertThat(out).extracting(RecommendItem::score).containsExactly(1.0,.98);
            assertThat(out.getFirst().recallFrom()).containsExactly("VECTOR","HOT");
            assertThat(out.getFirst().reason()).isEqualTo("与你的兴趣语义相近");
        }
    }
    @Test void nullOrAbsentVectorsFallBackToCategorySimilarity() {
        jdbc.execute("INSERT INTO item_embedding VALUES(1,'[1,0,0]'),(2,'[1,0,0]'),(3,NULL)");
        for(var reranker:rerankers())
            assertThat(reranker.rerank(CANDIDATES,input(2,3,"B"))).extracting(RecommendItem::itemId).containsExactly(1L,3L);
        jdbc.execute("DELETE FROM item_embedding WHERE item_id=3");
        for(var reranker:rerankers())
            assertThat(reranker.rerank(CANDIDATES,input(2,3,"B"))).extracting(RecommendItem::itemId).containsExactly(1L,3L);
    }
    @Test void entirelyMissingVectorsKeepEachAlgorithmsOriginalBehavior() {
        assertThat(new MmrReranker(mapper).rerank(CANDIDATES,input(2,3,"A")))
                .extracting(RecommendItem::itemId).containsExactly(1L,2L);
        assertThat(new DppReranker(mapper).rerank(CANDIDATES,input(2,3,"A")))
                .extracting(RecommendItem::itemId).containsExactly(1L);
    }
    @Test void missingTableKeepsCategoryFallbackAndSmallPoolsNeedNoQuery() {
        jdbc.execute("ALTER TABLE item_embedding RENAME TO unavailable_embeddings");
        try {
            for(var reranker:rerankers()) {
                assertThat(reranker.rerank(CANDIDATES,input(2,3,"B"))).extracting(RecommendItem::itemId).containsExactly(1L,3L);
                assertThat(reranker.rerank(CANDIDATES,input(3,2,"B"))).extracting(RecommendItem::itemId).containsExactly(1L,2L);
                assertThat(reranker.rerank(List.of(),input(3,200,"B"))).isEmpty();
            }
        } finally { jdbc.execute("ALTER TABLE unavailable_embeddings RENAME TO item_embedding"); }
    }
    @Test void candidatePoolLimitKeepsOutOfPoolVectorsFromChangingSelection() {
        jdbc.execute("INSERT INTO item_embedding VALUES(1,'[1,0,0]'),(2,'[1,0,0]'),(3,'[0,1,0]'),(4,'[0,0,1]')");
        for(var reranker:rerankers()) {
            assertThat(reranker.rerank(CANDIDATES,input(2,2,"A"))).extracting(RecommendItem::itemId).containsExactly(1L,2L);
            assertThat(reranker.rerank(CANDIDATES,input(2,3,"A"))).extracting(RecommendItem::itemId).containsExactly(1L,3L);
        }
    }
}
