package com.recsys.console.item;

import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
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
 * <p>复用 content 库的 {@link ContentService}(默认 {@code MybatisContentService} 读主库 {@code item} 表)。
 * 真实缺失返回已找到子集；读取失败返回安全503，标题补全的前端仍可回退 {@code #itemId}。
 */
@RestController
@RequestMapping("/api/console/items")
public class ItemController {

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
        } catch (RuntimeException failure) {
            // 失败不能证明目录为空；公开出口只使用目录消息，内部cause保留给诊断。
            throw new PublicErrorException(ErrorCode.CONSOLE_ITEM_QUERY_FAILED, failure);
        }
    }

    /** 单查物料元数据；仅确定不存在返回 {@code null}，不可读返回安全错误码。 */
    @GetMapping("/{id}")
    public ItemMeta byId(@PathVariable long id) {
        try {
            Item it = contentService.findById(id);
            return it == null ? null : toMeta(it);
        } catch (RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.CONSOLE_ITEM_QUERY_FAILED, failure);
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
