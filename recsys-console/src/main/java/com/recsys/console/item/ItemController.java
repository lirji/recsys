package com.recsys.console.item;

import com.recsys.content.ContentService;
import com.recsys.content.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 物料元数据只读端点(控制台把裸 {@code #itemId} 显示成真实标题/类目的地基)。
 * 走 /api/console/** —— 网关已路由到 console:8090,无需改网关。
 *
 * <p>复用 content 库的 {@link ContentService}(默认 {@code JdbcContentService} 读主库 {@code item} 表)。
 * DB 不可用/物品缺失一律优雅降级:返回已找到子集(空则空),让前端退回 {@code #itemId} 展示。
 */
@RestController
@RequestMapping("/api/console/items")
public class ItemController {

    private static final Logger log = LoggerFactory.getLogger(ItemController.class);

    /** 单次批量查上限:防超大 IN 与恶意请求;前端一页远不需要这么多。 */
    private static final int MAX_IDS = 200;

    private final ContentService contentService;

    public ItemController(ContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * 批量查物料元数据。{@code ids} 为逗号分隔的 itemId(非数字 token 静默跳过、去重、超限截断)。
     * 按请求顺序返回;缺失的 id 不出现在结果里。
     */
    @GetMapping
    public List<ItemMeta> byIds(@RequestParam(required = false) String ids) {
        List<Long> parsed = parseIds(ids);
        if (parsed.isEmpty()) {
            return List.of();
        }
        try {
            Map<Long, Item> found = contentService.findByIds(parsed);
            List<ItemMeta> out = new ArrayList<>(found.size());
            for (Long id : parsed) {          // 保请求序
                Item it = found.get(id);
                if (it != null) {
                    out.add(toMeta(it));
                }
            }
            return out;
        } catch (Exception e) {
            // DB 不可用:降级返回空,让前端退回 #itemId(不 500)
            log.warn("批量查物料失败,降级返回空: {}", e.getMessage());
            return List.of();
        }
    }

    /** 单查物料元数据,不存在或 DB 不可用返回 {@code null}。 */
    @GetMapping("/{id}")
    public ItemMeta byId(@PathVariable long id) {
        try {
            Item it = contentService.findById(id);
            return it == null ? null : toMeta(it);
        } catch (Exception e) {
            log.warn("查物料 {} 失败,降级返回 null: {}", id, e.getMessage());
            return null;
        }
    }

    /** 解析逗号分隔 id:去空白、跳非数字、去重(保序)、截断到 MAX_IDS。 */
    private static List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        LinkedHashSet<Long> uniq = new LinkedHashSet<>();
        for (String tok : ids.split(",")) {
            String s = tok.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                uniq.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // 非数字 token 静默跳过
            }
            if (uniq.size() >= MAX_IDS) {
                break;
            }
        }
        return new ArrayList<>(uniq);
    }

    private static ItemMeta toMeta(Item it) {
        return new ItemMeta(it.itemId(), it.title(), it.category(), it.tags());
    }
}
