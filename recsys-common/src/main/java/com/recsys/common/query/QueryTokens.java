package com.recsys.common.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 查询/文本的归一化分词——<b>在线 query 理解与离线 IDF 统计的单一真源</b>。
 *
 * <p>类比 {@code SparseFeatureEncoder}/{@code SequenceEncoder} 之于排序:IDF 是"离线按语料统计 df、
 * 在线按词项查表赋权"的契约,只有两侧<b>分词完全一致</b>才对得上。故把归一化+切词收敛到此纯函数,
 * 在线 {@code QueryUnderstandingServiceImpl} 与离线 {@code IdfJob} 都调它。
 *
 * <p>规则:小写(ROOT locale)→ 非字母数字(含中文按字符,连续符号折叠)一律作分隔 → 去空串。
 * 不去停用词、不截断——那是在线 query 理解的上层策略,不影响 df 统计口径(停用词 df 高→IDF 自然低)。
 */
public final class QueryTokens {

    /** 非字母数字(含中文)一律视为分隔符,与旧 QueryUnderstandingServiceImpl.NON_WORD 一致。 */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private QueryTokens() {
    }

    /** 归一化:小写 + 非词字符折叠为单空格 + 去首尾空白 + 压缩连续空格。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        s = NON_WORD.matcher(s).replaceAll(" ");
        return s.trim().replaceAll("\\s+", " ");
    }

    /** 归一化后切词(去空串);顺序保留,不去重。 */
    public static List<String> tokenize(String raw) {
        String norm = normalize(raw);
        if (norm.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : norm.split(" ")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
