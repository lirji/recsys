package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.springframework.boot.ApplicationArguments;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 离线作业重复CLI解析的受控入口；不将用户原始参数拼进公开失败提示。 */
final class JobArguments {
    private JobArguments() { }

    /** 明确提供但无值/多值/空白是错误，不能悄悄落到另一个有副作用的默认输入。 */
    static String text(ApplicationArguments args, String key, String fallback) {
        if (!args.containsOption(key)) return fallback;
        List<String> values = args.getOptionValues(key);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        }
        return values.get(0).trim();
    }

    /** 数值解析错误保留内部cause，公开边界仅展示固定目录。 */
    static int integer(ApplicationArguments args, String key, int fallback) {
        try { return Integer.parseInt(text(args, key, Integer.toString(fallback))); }
        catch (NumberFormatException failure) { throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT, failure); }
    }

    /** 大ID和时间戳直接按long解析，避免double转换丢失身份精度。 */
    static long longInteger(ApplicationArguments args, String key, long fallback) {
        try { return Long.parseLong(text(args, key, Long.toString(fallback))); }
        catch (NumberFormatException failure) { throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT, failure); }
    }

    /** 非有限数会污染权重与切分，和坏字面量一起在副作用前拒绝。 */
    static double decimal(ApplicationArguments args, String key, double fallback) {
        try {
            double value = Double.parseDouble(text(args, key, Double.toString(fallback)));
            if (!Double.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException failure) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT, failure);
        }
    }

    /** 不能用Boolean.parseBoolean把拼错的安全开关当false。 */
    static boolean bool(ApplicationArguments args, String key, boolean fallback) {
        String value = text(args, key, Boolean.toString(fallback));
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
    }

    /** 输入校验在调用方原变更数据之前执行；读取时仍需处理文件变化或IO失败。 */
    static void readableInput(Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new PublicErrorException(ErrorCode.JOB_INPUT_UNAVAILABLE);
        }
    }
}
