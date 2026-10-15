package com.recsys.common.ad.billing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.UUID;

/** 调整预算的不可变命令；actor 由可信管理端身份生成，不接受终端自行指定。 */
public record BudgetCommand(long advertiserId, UUID commandId, LocalDate budgetDate,
                            BigDecimal limitAmount, long expectedVersion, String actor) {
    public BudgetCommand {
        if (advertiserId <= 0 || commandId == null || budgetDate == null
                || budgetDate.getYear() < 1 || budgetDate.getYear() > 9999 || expectedVersion < 0
                || expectedVersion == Long.MAX_VALUE || actor == null || actor.isBlank() || actor.length() > 200) {
            throw new IllegalArgumentException("预算命令标识、版本或操作人非法");
        }
        limitAmount = CnyAmount.exact(limitAmount);
    }

    /** 严格解析边界字段，避免宽松 UUID/日期解析生成多个看似不同的幂等键。 */
    public static BudgetCommand parse(long advertiserId, String commandId, String budgetDate,
                                      String currency, String limitAmount, long expectedVersion, String actor) {
        if (!CnyAmount.CURRENCY.equals(currency)) {
            throw new IllegalArgumentException("首版仅支持 CNY");
        }
        return new BudgetCommand(advertiserId, parseId(commandId), parseDate(budgetDate),
                CnyAmount.parse(limitAmount), expectedVersion, actor);
    }

    /** 查询与写入共用规范 UUID 规则。 */
    public static UUID parseId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("commandId 须为 UUID");
        }
        return UUID.fromString(value);
    }

    /** 日期语义固定为 Asia/Shanghai 预算日，不从服务器默认时区推断。 */
    public static LocalDate parseDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("budgetDate 须为 ISO 日期");
        }
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1) throw new IllegalArgumentException("budgetDate 年份非法");
            return date;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("budgetDate 须为有效日期");
        }
    }

    /** actor 放在末尾，其余字段均有固定格式，换行分隔不存在负载拼接歧义。 */
    public String payloadHash() {
        String canonical = String.join("\n", BudgetAccount.TRAFFIC_CLASS, Long.toString(advertiserId),
                budgetDate.toString(), CnyAmount.CURRENCY, limitAmount.toPlainString(),
                Long.toString(expectedVersion), actor);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行时缺少 SHA-256", ex);
        }
    }
}
