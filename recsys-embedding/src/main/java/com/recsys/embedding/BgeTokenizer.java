package com.recsys.embedding;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java BERT WordPiece 分词器(BGE / bge-*-en-v1.5 使用标准 BERT uncased 分词)。
 *
 * <p>实现 HuggingFace {@code BertTokenizer} 的核心两步,与 Python 训练/导出侧严格一致——
 * 这是本地向量化的「在线/离线契约」,类比 {@code SparseFeatureEncoder} / {@code SequenceEncoder}:
 * <ol>
 *   <li><b>BasicTokenizer</b>:清洗控制字符 → (可选)小写 + 去重音(NFD 去 Mn)→ CJK 单字切分
 *       → 空白切分 → 标点独立成词。</li>
 *   <li><b>WordpieceTokenizer</b>:对每个词做贪心最长匹配(子词加 {@code ##} 前缀),
 *       超长或无法匹配 → {@code [UNK]}。</li>
 * </ol>
 * 最终拼 {@code [CLS] ... [SEP]},截断到 {@code maxLen},输出 input_ids / attention_mask / token_type_ids。
 *
 * <p>仅依赖 vocab.txt(行号即 token id),无任何 native 依赖,完全离线。
 */
final class BgeTokenizer {

    private static final String UNK = "[UNK]";
    private static final String CLS = "[CLS]";
    private static final String SEP = "[SEP]";
    private static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final boolean lowercase;
    private final int unkId;
    private final int clsId;
    private final int sepId;

    BgeTokenizer(Path vocabPath, boolean lowercase) throws IOException {
        this.vocab = loadVocab(vocabPath);
        this.lowercase = lowercase;
        Integer unk = vocab.get(UNK);
        Integer cls = vocab.get(CLS);
        Integer sep = vocab.get(SEP);
        if (unk == null || cls == null || sep == null) {
            throw new IllegalStateException("vocab.txt 缺少特殊 token [UNK]/[CLS]/[SEP]: " + vocabPath);
        }
        this.unkId = unk;
        this.clsId = cls;
        this.sepId = sep;
    }

    /** 单条文本编码结果(batch=1)。三个数组等长,长度 = 实际 token 数(已含 [CLS]/[SEP],未 padding)。 */
    record Encoded(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
        int length() {
            return inputIds.length;
        }
    }

    /** 编码单条文本到 ≤ maxLen 个 token([CLS] + 内容 + [SEP])。 */
    Encoded encode(String text, int maxLen) {
        List<Integer> ids = new ArrayList<>(Math.min(maxLen, 64));
        ids.add(clsId);
        int budget = maxLen - 2; // 给 [CLS]/[SEP] 留位
        outer:
        for (String word : basicTokenize(text)) {
            for (int id : wordpiece(word)) {
                if (ids.size() - 1 >= budget) {
                    break outer; // 截断
                }
                ids.add(id);
            }
        }
        ids.add(sepId);

        int n = ids.size();
        long[] inputIds = new long[n];
        long[] mask = new long[n];
        long[] types = new long[n];
        for (int i = 0; i < n; i++) {
            inputIds[i] = ids.get(i);
            mask[i] = 1L;
            types[i] = 0L;
        }
        return new Encoded(inputIds, mask, types);
    }

    // ---- BasicTokenizer ----

    private List<String> basicTokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cleaned = new StringBuilder(text.length());
        // 清洗 + CJK 单字两侧加空格
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0 || c == 0xFFFD || isControl(c)) {
                continue;
            }
            if (isWhitespace(c)) {
                cleaned.append(' ');
            } else if (isCjk(c)) {
                cleaned.append(' ').append(c).append(' ');
            } else {
                cleaned.append(c);
            }
        }
        for (String piece : cleaned.toString().trim().split("\\s+")) {
            if (piece.isEmpty()) {
                continue;
            }
            String tok = piece;
            if (lowercase) {
                tok = stripAccents(tok.toLowerCase());
            }
            splitOnPunctuation(tok, tokens);
        }
        return tokens;
    }

    private static void splitOnPunctuation(String word, List<String> out) {
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (isPunctuation(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(String.valueOf(c));
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
    }

    private static String stripAccents(String s) {
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- WordpieceTokenizer ----

    private List<Integer> wordpiece(String word) {
        List<Integer> output = new ArrayList<>();
        if (word.length() > MAX_CHARS_PER_WORD) {
            output.add(unkId);
            return output;
        }
        int start = 0;
        int n = word.length();
        boolean bad = false;
        List<Integer> sub = new ArrayList<>();
        while (start < n) {
            int end = n;
            Integer curId = null;
            while (start < end) {
                String piece = (start > 0 ? "##" : "") + word.substring(start, end);
                Integer id = vocab.get(piece);
                if (id != null) {
                    curId = id;
                    break;
                }
                end--;
            }
            if (curId == null) {
                bad = true;
                break;
            }
            sub.add(curId);
            start = end;
        }
        if (bad) {
            output.add(unkId);
        } else {
            output.addAll(sub);
        }
        return output;
    }

    // ---- 字符类别(对齐 HF BERT 定义)----

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || Character.isSpaceChar(c);
    }

    private static boolean isControl(char c) {
        if (c == '\t' || c == '\n' || c == '\r') {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.CONTROL || type == Character.FORMAT;
    }

    private static boolean isPunctuation(char c) {
        // ASCII 标点(BERT 把它们都当标点)
        if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static Map<String, Integer> loadVocab(Path vocabPath) throws IOException {
        Map<String, Integer> map = new HashMap<>(40000);
        try (BufferedReader r = Files.newBufferedReader(vocabPath, StandardCharsets.UTF_8)) {
            String line;
            int idx = 0;
            while ((line = r.readLine()) != null) {
                // vocab.txt 每行一个 token,行号即 id;token 本身不含换行,去尾部 \r 即可
                String tok = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                map.put(tok, idx++);
            }
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("vocab.txt 为空: " + vocabPath);
        }
        return map;
    }
}
