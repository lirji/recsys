package com.recsys.advertiser.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** 目录命令和本地步骤的稳定身份；租约只接管原命令，不代替持久广告主占有。 */
public final class CatalogProtocol {
    public static final String TOPOLOGY = "advertiser-mod2-child-ad-mod2-v1";
    /** 跨命令类型复用的持久阶段名；与已有SQL协议值一致，不改变已保存输入。 */
    public static final String PHASE_PREPARE="PREPARE", PHASE_SEND="SEND";
    public static final int STEP_ATTEMPTS = 3;
    public static final int SEND_ATTEMPTS = 5;
    public static final int PAYLOAD_BYTES = 512 * 1024;
    public static final int LEASE_SECONDS = 30;
    private CatalogProtocol() { }

    /** 操作集合为C16冻结范围；状态fanout另由S16B接入。 */
    public enum Kind { ROOT_CREATE, ROOT_UPDATE, AD_CREATE, AD_UPDATE, AD_STATUS, AD_REVIEW,
        AD_SUBMIT_REVIEW, AD_DELETE, BIDWORD_CREATE, BIDWORD_UPDATE, BIDWORD_DELETE,
        CREATIVE_CREATE, CREATIVE_UPDATE, CREATIVE_DELETE, REFRESH_ENRICHMENT, INITIALIZE, RETRY_STEP,
        REPLAY_EVENT, RECHECK }
    public enum State { ADMITTED, RUNNING, COMPLETED, PAUSED, REJECTED }
    /** 本库Outbox确认状态，与命令/step的终态属于不同生命周期。 */
    public static final String OUTBOX_PUBLISHED="PUBLISHED";
    public enum StepState { READY, RUNNING, APPLIED, PAUSED }
    public enum ScopeKind { ADVERTISER, ROOT_CREATE, INITIALIZE, RECOVERY, STATUS_BATCH }
    public enum Failure { NOT_FOUND, CONFLICT, NOT_READY, FENCED, PAUSED, INCONSISTENT, SNAPSHOT_LIMIT, RESULT_UNKNOWN, FANOUT_NOT_AVAILABLE, INVALID_INPUT }

    /** 固定home是身份的一部分；开户尚无owner时仍能定位同一命令。 */
    public record CommandKey(int shard, UUID source, ScopeKind scopeKind, String scopeId, UUID command) {
        public CommandKey {
            requireShard(shard); Objects.requireNonNull(source); Objects.requireNonNull(scopeKind); Objects.requireNonNull(command);
            if(scopeId==null || scopeId.isBlank() || scopeId.length()>100)throw new IllegalArgumentException("无效目录命令作用域");
        }
        public String scope() { return scopeKind == ScopeKind.INITIALIZE ? "INITIALIZE" : scopeKind+":"+scopeId; }
        public String stepKey(String phase) { return source+":"+scope()+":"+command+":"+phase; }
        public static CommandKey advertiser(UUID source,long advertiser,UUID command) {
            if(advertiser<=0)throw new IllegalArgumentException("广告主身份必须为正");
            return new CommandKey((int)(advertiser%2),source,ScopeKind.ADVERTISER,Long.toString(advertiser),command);
        }
        public static CommandKey root(UUID source,UUID command) {
            return new CommandKey((int)(command.getLeastSignificantBits()&1),source,ScopeKind.ROOT_CREATE,"ROOT_CREATE",command);
        }
    }

    /** 原请求只冻结一次，调用者不能通过修改byte数组改变已经验证的请求。 */
    public record Submission(CommandKey key,Kind kind,byte[] request,String actor,String reason) {
        public Submission {
            Objects.requireNonNull(key); Objects.requireNonNull(kind); requireBytes(request); request=request.clone();
            if(actor==null || actor.isBlank() || actor.length()>200 || reason==null || reason.isBlank() || reason.length()>500)
                throw new IllegalArgumentException("目录命令需要有界可信审计主体与原因");
        }
        @Override public byte[] request(){return request.clone();}
        public String digest(){return sha256(request);}
    }

    /** 数据库原结果只作为已提交事实读取；不根据当前源表重新生成历史响应。 */
    public record CommandRow(int shardId, UUID sourceId, String scopeKind, String scopeId, UUID commandId,
                             String kind, byte[] requestBytes, String requestSha256, String actor, String reason,
                             Long advertiserId, Long adId, String state, String phase, String activeStep,
                             int round, int attempts, long fence, Instant leaseUntil, Instant availableAt,
                             byte[] frozenBytes, byte[] resultBytes, String errorType) {
        public CommandKey key(){return new CommandKey(shardId,sourceId,ScopeKind.valueOf(scopeKind),scopeId,commandId);}
        public boolean completed(){return State.COMPLETED.name().equals(state);}
    }

    /** 跨库派发的输入固定到原scope/command/step，不能以新worker身份或新请求接管。 */
    public record StepRequest(int shard, CommandKey commandKey, String stepId, long advertiserId, Long adId,
                              Long aggregateVersion, byte[] request, String writePlan) {
        public StepRequest {
            requireShard(shard); Objects.requireNonNull(commandKey); requireBytes(request); request=request.clone();
            if(advertiserId<=0 || stepId==null || !stepId.matches("[A-Z_0-9]{1,40}") || writePlan==null)
                throw new IllegalArgumentException("无效目录本地步骤");
        }
        @Override public byte[] request(){return request.clone();}
        public String stepKey(){return commandKey.stepKey(stepId);}
        public String digest(){return sha256(request);}
    }

    /** APPLIED后此记录不再改写，fence只管对应实际物理库。 */
    public record StepRow(int shardId, UUID sourceId, String stepKey, int ownerShard, String commandScope,
                          UUID commandId, String stepId, long advertiserId, Long adId, Long aggregateVersion,
                          byte[] requestBytes, String requestSha256, String writePlan, String state, int round,
                          int attempts, long fence, Instant leaseUntil, Instant availableAt, byte[] resultBytes,
                          String resultSha256, String effects, String outboxId, String writeTxid, String errorType) {
        public boolean applied(){return StepState.APPLIED.name().equals(state);}
    }

    /** 对外只翻译枚举；底层SQL、业务原文和连接信息不进入错误响应。 */
    public static final class CatalogException extends RuntimeException {
        private final Failure failure;
        public CatalogException(Failure failure){super("目录操作："+failure);this.failure=failure;}
        public Failure failure(){return failure;}
    }
    public static CatalogException failure(Failure failure){return new CatalogException(failure);}
    public static void requireShard(int shard){if(shard!=0 && shard!=1)throw new IllegalArgumentException("无效目录分片");}
    public static void requireBytes(byte[] bytes){if(bytes==null || bytes.length==0 || bytes.length>PAYLOAD_BYTES)throw failure(Failure.SNAPSHOT_LIMIT);}
    public static String sha256(byte[] value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("JVM缺少SHA-256",e);}
    }
    public static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}
}
