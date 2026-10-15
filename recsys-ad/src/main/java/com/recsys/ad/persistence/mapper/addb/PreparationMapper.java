package com.recsys.ad.persistence.mapper.addb;

import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 准备检查点只按封闭计费模式选固定表；所有语句保留原5秒查询超时。 */
public interface PreparationMapper {
    /** 版本由Repository固定映射，不接收外部表名或SQL。 */
    List<String> migrationHashes(@Param("version") int version);
    /** 校验选定模式的必要列，不自动迁移。 */
    void verifyPreparation(@Param("mode") ChargeMode mode);
    /** 恢复期限采用owner数据库当前时间。 */
    OffsetDateTime now();
    /** 独立原子领取；冲突不覆盖原输入。 */
    int claim(@Param("mode") ChargeMode mode, @Param("row") Claim row);
    /** 领取后按原始JSON验证完整请求一致。 */
    List<String> requests(@Param("mode") ChargeMode mode, @Param("id") UUID id);
    /** 包含主体，即使结果尚为空也要执行鉴权。 */
    List<StoredResult> find(@Param("mode") ChargeMode mode, @Param("id") UUID id);
    /** 首个完成者胜出；失败或竞争后由Repository查询原结果。 */
    int complete(@Param("mode") ChargeMode mode, @Param("id") UUID id, @Param("resultJson") String resultJson);
    /** 同时读取原请求与结果，恢复前必须核对不可变负载。 */
    List<Original> original(@Param("mode") ChargeMode mode, @Param("id") UUID id);

    /** requestJson是已有受限TEXT协议，保留ObjectMapper既有编码。 */
    record Claim(UUID childId, UUID operationId, String subject, int slot, String requestJson) { }
    /** resultJson可空，不能把未完成记录误认为没有主体。 */
    record StoredResult(String subject, String resultJson) { }
    /** 敏感输入只用于恢复与校验，不向公开查询返回。 */
    record Original(String subject, String requestJson, String resultJson) { }
}
