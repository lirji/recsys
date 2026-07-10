package com.recsys.common.content;

import java.util.Collection;
import java.util.List;

/**
 * item 目录**逐候选热路径读**抽象(#3 content 上下文拆库):把"直读 {@code item} 表"的召回/排序/query 读者
 * 与来源解耦——展示 hydration 已由 P2 的 {@code BatchGetItems} gRPC 拆走,但逐候选热路径(LEXICAL 全文检索 /
 * TAG 类目 / 冷启动 / rank 长历史类目 / query 意图)一次请求要给数百候选算分,<b>逐候选 gRPC 会击穿毫秒预算</b>,
 * 故必须读<b>本地读模型</b>。
 *
 * <p>两种来源,用 {@code recsys.content.item-source} 一键切换/回滚(与 {@code AdCatalogReader}/
 * {@code UserProfileReader}/{@code SeenItemsSource} 同 {@code @ConditionalOnProperty} 范式):
 * <ul>
 *   <li>{@code db}(默认,{@code matchIfMissing}):{@code DbItemCatalogReader} 直读共享 {@code item}(回滚落点,行为不变)。</li>
 *   <li>{@code replica}:{@code ReplicaItemCatalogReader} 读本地副本 {@code item_local}(离线 {@code sync-item-catalog}
 *       从 content 权威库灌入)—— item 物理搬到 content 自有库后,消费方不再跨库直读。</li>
 * </ul>
 * 实现放 {@code recsys-content}(所有读 item 的 app 都依赖它);SQL 逐字搬自各读者、只把表名参数化,保证 golden-diff 无损。
 *
 * <p>注:{@link #recentTitles}/{@link #recentRatedCategories} 还 join {@code user_behavior}(取用户近期 item 的
 * title/category)——behavior 侧读是另一条缝(不在本刀),这里只把其中的 item 表名参数化、join 原样保留。
 */
public interface ItemCatalogReader {

    /** LEXICAL 词法/BM25 召回:{@code title_tsv} 全文检索 {@code ts_rank_cd} 打分,(itemId, score) Top-limit。 */
    List<ScoredId> lexicalSearch(String query, int limit);

    /** TAG 类目召回:给定类目集内按 popularity 取 Top-limit,带 category 供在线加权。 */
    List<CatItem> byCategories(Collection<String> categories, int limit);

    /** 冷启动:每类目热度前 perCategory 名(rn),整体按 (rn, popularity) 交错取 Top-limit,带 category/rank 供 UCB。 */
    List<ColdItem> coldStartByCategory(int perCategory, int limit);

    /** 热门兜底:按 popularity 取 Top-limit(HOT 路 Redis 皆空时的库直读)。 */
    List<ScoredId> hotByPopularity(int limit);

    /** 用户近期正反馈 item 的标题(SemanticRecaller 伪 query;join user_behavior)。 */
    List<String> recentTitles(long userId, int limit);

    /** 用户近 limit 条 RATING≥4 的 (item_id, category)(SimRankService GSU 长历史;join user_behavior)。 */
    List<CatId> recentRatedCategories(long userId, int limit);

    /** query 意图:标题 ILIKE ANY(patterns) 的 (category, count),按 count 降序取 Top-limit。 */
    List<CatCount> categoryCountsByTitleLike(List<String> ilikePatterns, int limit);

    /** 全部去重 category(query 理解 genre 词典)。 */
    List<String> distinctCategories();

    record ScoredId(long itemId, double score) {}

    record CatItem(long itemId, String category, double popularity) {}

    record ColdItem(long itemId, String category, double popularity, int rank) {}

    record CatId(long itemId, String category) {}

    record CatCount(String category, long count) {}
}
