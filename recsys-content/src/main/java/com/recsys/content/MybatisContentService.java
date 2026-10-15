package com.recsys.content;

import com.recsys.content.persistence.mapper.ItemMapper;
import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.content.persistence.model.ItemRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Mapper/XML 的物品服务实现,默认读共享 {@code item} 表。
 *
 * <p>#3 content 拆库:{@code findByIds} 是排序逐候选类目特征(rank×10 + ad CVR)的热路径读。item 表名由
 * {@link #table()} 给,{@code recsys.content.item-source=replica} 时激活的子类 {@link ReplicaMybatisContentService}
 * 覆盖为读本地副本 {@code item_local}。owner(content-service)不设该开关 → 默认 {@code db} → 读权威 {@code item}。
 */
@Service
@ConditionalOnProperty(name = "recsys.content.item-source", havingValue = "db", matchIfMissing = true)
public class MybatisContentService implements ContentService {

    private final ItemMapper items;

    // item 元数据近乎静态(title/category/tags/popularity 极少变),却在一次推荐里被粗排/精排/重排
    // 分别查库(每次全列 SELECT,含 description/tags 重字段)。进程内 TTL 缓存把 2、3 次重复读变成
    // 内存命中,并跨请求复用热门 item;save() 就地失效。默认开,可 recsys.content.cache.enabled=false 关。
    @Value("${recsys.content.cache.enabled:true}")
    private boolean cacheEnabled = true;
    @Value("${recsys.content.cache.ttl-seconds:300}")
    private long cacheTtlSeconds = 300;
    @Value("${recsys.content.cache.max-size:50000}")
    private int cacheMaxSize = 50000;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public MybatisContentService(ItemMapper items) {
        this.items = items;
    }

    /** 缓存条目:item + 过期时刻(毫秒)。 */
    private record CacheEntry(Item item, long expiresAt) {
    }

    /** item 目录表名:{@code item}(共享,默认)或子类覆盖为 {@code item_local}(本地副本)。 */
    protected CatalogTable table() {
        return CatalogTable.OWNED;
    }

    @Override
    public Item findById(long itemId) {
        if (cacheEnabled) {
            CacheEntry e = cache.get(itemId);
            if (e != null && e.expiresAt() > now()) {
                return e.item();
            }
        }
        Item it = fetchById(itemId);
        if (cacheEnabled && it != null) {
            putCache(itemId, it);
        }
        return it;
    }

    @Override
    public Map<Long, Item> findByIds(List<Long> itemIds) {
        Map<Long, Item> result = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return result;
        }
        if (!cacheEnabled) {
            return fetchByIds(itemIds);
        }
        // 命中缓存的直接取,未命中(或过期)的收集起来一次性查库回填
        long now = now();
        List<Long> misses = new ArrayList<>();
        for (Long id : itemIds) {
            CacheEntry e = cache.get(id);
            if (e != null && e.expiresAt() > now) {
                result.put(id, e.item());   // 命中(item 非 null:仅缓存存在的 item,与"不在 map 中"契约一致)
            } else {
                misses.add(id);
            }
        }
        if (!misses.isEmpty()) {
            Map<Long, Item> fetched = fetchByIds(misses);
            if (!fetched.isEmpty()) {
                maybeEvict();
                long exp = now + cacheTtlSeconds * 1000L;
                for (Item it : fetched.values()) {
                    cache.put(it.itemId(), new CacheEntry(it, exp));
                }
            }
            result.putAll(fetched);
        }
        return result;
    }

    /** 直读单个 item(不经缓存)。 */
    private Item fetchById(long itemId) {
        ItemRow row = table() == CatalogTable.OWNED ? items.selectById(itemId) : items.findById(table(), itemId);
        return row == null ? null : row.toItem();
    }

    /** 直读一批 item(不经缓存),返回 id->Item(不存在的 id 不在 map 中)。 */
    private Map<Long, Item> fetchByIds(List<Long> itemIds) {
        Map<Long, Item> result = new HashMap<>();
        for (ItemRow row : items.findByIds(table(), itemIds)) {
            Item it = row.toItem();
            result.put(it.itemId(), it);
        }
        return result;
    }

    private void putCache(long id, Item it) {
        maybeEvict();
        cache.put(id, new CacheEntry(it, now() + cacheTtlSeconds * 1000L));
    }

    /** 超过上限时先清过期项,仍超限则整表清空(item 目录有界,极少触发;防无界增长)。 */
    private void maybeEvict() {
        if (cache.size() < cacheMaxSize) {
            return;
        }
        long now = now();
        cache.entrySet().removeIf(en -> en.getValue().expiresAt() <= now);
        if (cache.size() >= cacheMaxSize) {
            cache.clear();
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    @Override
    public List<Long> allItemIds() {
        return items.allItemIds(table());
    }

    @Override
    public void save(Item item) {
        items.upsert(table(), item, item.tags() == null ? List.of() : item.tags());
        cache.remove(item.itemId());   // 写后失效:下次读回填最新
    }

    @Override
    public long count() {
        return table() == CatalogTable.OWNED ? items.selectCount(null) : items.countFrom(table());
    }
}
