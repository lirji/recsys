package com.recsys.recengine.filter.seen;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** 旧7/8字段严格分类；不让Jackson把浮点/字符串ID静默转成长整数。 */
public final class SeenMessageDecoder {
    public static final int MAX_VALUE = 32768;
    public static final int MAX_KEY = 1024;
    private static final Set<String> FIELDS = Set.of("userId", "itemId", "action", "value", "scene", "bucket", "ts", "exposureId");
    private static final Set<String> POSITIVE = Set.of("CLICK", "LIKE", "PLAY", "RATING");
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    public record Decoded(String classification, String error, long userId, long itemId) { }

    /** 旧key不参与合法性判定；超限单列且完整原文保存由仓储决定。 */
    public Decoded decode(byte[] key, byte[] value) {
        if ((key != null && key.length > MAX_KEY) || (value != null && value.length > MAX_VALUE)) return invalid("OVERSIZE");
        if (value == null) return invalid(SeenRecord.Code.INVALID_DATA.name());
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
            JsonNode n;
            try (var parser = json.createParser(text)) {
                n = json.readTree(parser);
                if (parser.nextToken() != null) return invalid(SeenRecord.Code.INVALID_DATA.name());
            }
            if (n == null || !n.isObject()) return invalid(SeenRecord.Code.INVALID_DATA.name());
            var names = n.fieldNames(); while (names.hasNext()) if (!FIELDS.contains(names.next())) return invalid(SeenRecord.Code.INVALID_DATA.name());
            if (!positiveLong(n.get("userId")) || !positiveLong(n.get("itemId"))) return invalid(SeenRecord.Code.INVALID_DATA.name());
            JsonNode action = n.get("action");
            if (action == null || !action.isTextual() || (!POSITIVE.contains(action.textValue()) && !"IMPRESSION".equals(action.textValue()))) return invalid(SeenRecord.Code.INVALID_DATA.name());
            if (n.has("value") && (!n.get("value").isNumber() || !Double.isFinite(n.get("value").doubleValue()))) return invalid(SeenRecord.Code.INVALID_DATA.name());
            if (n.has("ts") && (!n.get("ts").isIntegralNumber() || !n.get("ts").canConvertToLong())) return invalid(SeenRecord.Code.INVALID_DATA.name());
            for (String field : Set.of("scene", "bucket", "exposureId"))
                if (n.hasNonNull(field) && !n.get(field).isTextual()) return invalid(SeenRecord.Code.INVALID_DATA.name());
            return new Decoded(POSITIVE.contains(action.textValue()) ? SeenRecord.Classification.POSITIVE.name() : SeenRecord.Classification.NO_EFFECT.name(), null, n.get("userId").longValue(), n.get("itemId").longValue());
        } catch (com.fasterxml.jackson.core.JsonProcessingException | CharacterCodingException badInput) {
            return invalid(SeenRecord.Code.INVALID_DATA.name());
        } catch (java.io.IOException unexpected) { throw new SeenRecord.Failure(SeenRecord.Code.INTERNAL_ERROR); }
    }
    private boolean positiveLong(JsonNode n) { return n != null && n.isIntegralNumber() && n.canConvertToLong() && n.longValue() > 0; }
    private Decoded invalid(String error) { return new Decoded(SeenRecord.Code.INVALID_DATA.name(), error, 0, 0); }
    /** null与空字节分开编码，摘要仅防止坐标被不同原文覆盖。 */
    public static String digest(byte[] key, byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] bytes : new byte[][]{key, value}) {
                digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("缺少SHA256"); }
    }
}
