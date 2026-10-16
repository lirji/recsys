package com.recsys.advertiser.persistence.mapper.primary;

import com.recsys.advertiser.persistence.model.GeneratedId;
import org.apache.ibatis.annotations.Param;
import java.util.UUID;
import com.recsys.advertiser.catalog.CatalogProtocol.CommandKey;
import com.recsys.advertiser.catalog.CatalogProtocol.CommandRow;
import com.recsys.advertiser.catalog.CatalogProtocol.Submission;
import com.recsys.advertiser.catalog.CatalogProtocol.StepRequest;
import com.recsys.advertiser.catalog.CatalogProtocol.StepRow;
import com.recsys.advertiser.catalog.CatalogOutboxRepository.EventRow;
import com.recsys.advertiser.catalog.CatalogOutboxRepository.PendingEvent;
import java.util.List;

/** 可靠目录只执行显式单物理分片SQL；调用者不得在一个事务混用两个shard。 */
public interface CatalogLocalMapper {
    /** 最多固定枚举组合，原记录计数由数据库按一秒语句预算读取。 */
    String statistics(@Param("shard") int shard);
    /** 稳定有界候选；不同owner失败不会永远阻塞同分片其他候选。 */
    List<CommandRow> candidates(@Param("shard") int shard,@Param("source") UUID source);
    /** 查询仅返回脱敏摘要，游标和limit均由受控服务验证。 */
    List<StepSummary> stepSummaries(@Param("shard") int shard,@Param("key") CommandKey key,@Param("after") String after,@Param("limit") int limit);
    List<EventSummary> eventSummaries(@Param("shard") int shard,@Param("key") CommandKey key,@Param("after") String after,@Param("limit") int limit);
    record StepSummary(int shardId,String stepKey,String stepId,String state,int round,int attempts,String errorType) { }
    record EventSummary(int shardId,String outboxId,long aggregateVersion,String state,int round,int attempts,Integer brokerPartition,Long brokerOffset,String errorType) { }
    /** 两个实际shard分别读取绑定，不能从SINGLE误认两份control。 */
    Binding binding(@Param("shard") int shard);
    String schemaChecksum(@Param("shard") int shard);
    boolean compatibleSchema(@Param("shard") int shard);
    record Binding(int shardId,UUID sourceId,String topologyVersion,String clusterId,String topicId,String topicName,
                   String mode,UUID initializingCommandId,boolean initializationReady,String writerManifest,String stopReason) { }
    /** 每个本地事务先在目标物理连接设置锁和语句预算，不取得行锁。 */
    String prepareTransaction(@Param("shard") int shard,@Param("lockTimeout") String lockTimeout,@Param("statementTimeout") String statementTimeout);

    /** 开户或初始化可建立guard；占有内容必须来自原命令而非后台身份。 */
    int insertGuard(@Param("shard") int shard,@Param("owner") long owner,@Param("home") Integer home,
                    @Param("scope") String scope,@Param("command") UUID command);
    /** 根字段实际改变时递增根版本，拒绝溢出。 */
    int advanceRootVersion(@Param("shard") int shard,@Param("owner") long owner,@Param("version") long version);
    /** 仅该命令完成所有既定步骤后释放自己的占有，不能全局解锁。 */
    int releaseGuard(@Param("shard") int shard,@Param("owner") long owner,@Param("key") CommandKey key);
    /** 最终原响应与owner事件在同事务完成；过期协调者不能标成功。 */
    int finishCommand(@Param("key") CommandKey key,@Param("fence") long fence,@Param("result") byte[] result);
    /** 生成ID归属只填一次，和业务/receipt同库同事务。 */
    int bindStepAd(@Param("shard") int shard,@Param("stepKey") String stepKey,@Param("ad") long ad);
    /** 只允许确认没有本地步骤业务效果的新命令拒绝；业务效果存在时必须保留待办。 */
    int rejectUnstarted(@Param("key") CommandKey key,@Param("fence") long fence,@Param("error") String error);
    /** 使用真实路由键读取控制行；不通过SINGLE猜测安装状态。 */
    Control control(@Param("shard") int shard);

    /** 事务开始先持control共享锁，和维护窗口封闭写入互斥。 */
    Control lockControl(@Param("shard") int shard);

    /** 命令总是按固定home与完整scope读取，不用新生成ID猜测原结果。 */
    CommandRow command(@Param("key") CommandKey key);

    /** 锁住原命令后推进；不把同UUID的其他作用域合并。 */
    CommandRow lockCommand(@Param("key") CommandKey key);

    /** 原请求、原主体与命令行先于远端副作用提交。 */
    int insertCommand(@Param("submission") Submission submission, @Param("advertiserId") Long advertiserId);

    /** 广告主一次只能持久占有一个命令，跨命令竞争不靠短租约放行。 */
    Guard lockGuard(@Param("shard") int shard, @Param("advertiserId") long advertiserId);

    /** 仅从没有占有者的guard进入原命令；必须核对影响行数。 */
    int acquireGuard(@Param("key") CommandKey key, @Param("advertiserId") long advertiserId);

    /** 开户身份在原home一次冻结，与命令和预留ID同事务提交。 */
    int bindIdentity(@Param("key") CommandKey key, @Param("advertiserId") long advertiserId);

    /** 最后一次领取退出也必须持久清算，不能永远RUNNING。 */
    int pauseSpentCommand(@Param("key") CommandKey key);

    /** 先持久计次再执行一步，数据库时间限制接管和退避。 */
    int claimCommand(@Param("key") CommandKey key);

    /** 只有本轮owner令牌可保存已核验片段并进入下一步。 */
    int advanceCommand(@Param("key") CommandKey key, @Param("fence") long fence,
                       @Param("nextPhase") String nextPhase, @Param("activeStep") String activeStep,
                       @Param("frozen") byte[] frozen);

    /** 外部依赖失败保留原占有，按当前轮次剩余预算退避。 */
    int failCommand(@Param("key") CommandKey key, @Param("fence") long fence, @Param("error") String error);

    /** 本地步骤登记同一不可变输入，重复派发先查原步骤。 */
    int insertStep(@Param("step") StepRequest step);

    /** 只从目标物理库取原receipt，不用当前业务快照替代历史结果。 */
    StepRow step(@Param("shard") int shard, @Param("stepKey") String stepKey);

    /** 本地步骤领取与业务apply是两个短事务。 */
    int claimStep(@Param("shard") int shard, @Param("stepKey") String stepKey);

    /** 步骤第三次退出到期后置PAUSED，仍保留上层广告主占有。 */
    int pauseSpentStep(@Param("shard") int shard, @Param("stepKey") String stepKey);

    /** 仅选本库到期且耗尽的原步骤，批量上限固定；不领取新的业务执行权。 */
    java.util.List<String> expiredSpentSteps(@Param("shard") int shard, @Param("source") java.util.UUID source);

    /** 失败只记安全分类，旧fence不能改变新一轮检查点。 */
    int failStep(@Param("shard") int shard, @Param("stepKey") String stepKey,
                 @Param("fence") long fence, @Param("error") String error);

    /** 完整事件与owner最终业务/版本共事务追加。 */
    int insertKafkaEvent(@Param("event") PendingEvent event);
    /** 本库最多10个待发/过期候选，不能误领COORDINATOR片段。 */
    List<String> lockReadyEventIds(@Param("shard") int shard);
    /** 查询原事件和原发送状态。 */
    EventRow event(@Param("shard") int shard,@Param("id") String id);
    /** 先持久增加发送预算与fence再离开事务。 */
    int claimEvent(@Param("shard") int shard,@Param("id") String id);
    /** 第五次领取退出的到期记录必须清算。 */
    int pauseSpentEvent(@Param("shard") int shard,@Param("id") String id);
    /** 真实Kafka元数据只归属于有效的发送fence。 */
    int markEventPublished(@Param("shard") int shard,@Param("id") String id,@Param("fence") long fence,
                           @Param("partition") int partition,@Param("offset") long offset);
    /** 有限退避，超时保留原文与原eventId。 */
    int failEvent(@Param("shard") int shard,@Param("id") String id,@Param("fence") long fence,@Param("error") String error);
    /** 冲突停止只作用于本物理shard，不能静默清除其他未决占有。 */
    int stopShard(@Param("shard") int shard,@Param("error") String error);

    /** 在目标物理库锁定本步骤并设置事务局部上下文。 */
    String enterStep(@Param("shard") int shard, @Param("stepKey") String stepKey, @Param("fence") long fence);

    /** 开户在固定home预留原Snowflake ID；重复必须先查原命令。 */
    int reserveIdentity(@Param("shard") int shard, @Param("source") UUID source,
                        @Param("command") UUID command, @Param("key") GeneratedId key);

    /** 响应未知只读同一预留，不分配第二个身份。 */
    Long identity(@Param("shard") int shard, @Param("source") UUID source, @Param("command") UUID command);

    /** 修改必须同时带advertiser路由和ad身份，禁止广播更新。 */
    int updateAdTitle(@Param("advertiserId") long advertiserId, @Param("adId") long adId, @Param("title") String title);

    /** 广告ID仍由原分片Snowflake生成，归属路由在生成前已经固定。 */
    int insertAd(@Param("key") GeneratedId key, @Param("advertiserId") long advertiserId,
                  @Param("itemId") long itemId, @Param("title") String title, @Param("landingUrl") String landingUrl,
                  @Param("qualityScore") double qualityScore, @Param("status") String status,
                  @Param("reviewStatus") String reviewStatus, @Param("reviewReason") String reviewReason,
                  @Param("optimizationType") String optimizationType, @Param("targetCpa") Double targetCpa,
                  @Param("audienceId") Long audienceId);

    /** 只在同一步新建广告后回填默认落地页；guard校验实际生成ID。 */
    int setAdLanding(@Param("advertiserId") long advertiserId, @Param("adId") long adId, @Param("url") String url);

    /** 子资源按ad_id路由，不继承广告主的物理位置。 */
    int insertBidword(@Param("key") GeneratedId key, @Param("adId") long adId, @Param("keyword") String keyword,
                      @Param("matchType") String matchType, @Param("bid") double bid, @Param("bidMode") String bidMode);

    /** 创意保留数据库原审核默认值，不能由可靠写路径自动批准。 */
    int insertCreative(@Param("key") GeneratedId key, @Param("adId") long adId, @Param("title") String title,
                       @Param("landingUrl") String landingUrl, @Param("status") String status);

    /** 本库原文只插入一次；发送状态更新不能改写这些字段。 */
    int insertFact(@Param("shard") int shard, @Param("outbox") String outbox, @Param("source") UUID source,
                   @Param("scope") String scope, @Param("command") UUID command, @Param("stepId") String stepId,
                   @Param("payload") byte[] payload, @Param("sha256") String sha256);

    /** 原结果与事实同事务冻结；有效本地fence限制迟到执行器。 */
    int completeStep(@Param("shard") int shard, @Param("stepKey") String stepKey, @Param("fence") long fence,
                     @Param("result") byte[] result, @Param("sha256") String sha256, @Param("effects") String effects,
                     @Param("outbox") String outbox);

    /** 仅包含启动和路由核验必需的控制状态，不暴露连接凭据。 */
    record Control(int shardId, String sourceId, String mode, String topologyVersion, boolean initializationReady, String stopReason) { }
    /** 占有与advertiser版本同属本库owner。 */
    record Guard(int shardId, long advertiserId, long advertiserVersion, UUID activeCommandId,
                 Integer activeCommandHome, String activeCommandScope) { }
}
