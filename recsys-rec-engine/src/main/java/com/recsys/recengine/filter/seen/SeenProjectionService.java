package com.recsys.recengine.filter.seen;

import com.recsys.common.constant.RedisKeys;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.ThreadLocalRandom;
import static com.recsys.recengine.filter.seen.SeenRecord.Code.*;

/** 真实SADD→PG结果→listener ACK；任何未知结果都不会转换成合法无效果。 */
public class SeenProjectionService {
    public enum Outcome { ACK, WAIT, PAUSED }
    enum Phase { CLASSIFIED, CLAIMED, REDIS_APPLIED, PG_APPLIED }
    /** 测试只在真实副作用边界注入退出，不替换产品副作用。 */
    interface Probe { void at(Phase phase, SeenRecord record); }
    private final SeenRecoveryRepository repository;
    private final SeenMessageDecoder decoder;
    private final StringRedisTemplate redis;
    private final Probe probe;
    /** 产品默认无故障断点，真实Redis和持久仓储缺一不可。 */
    public SeenProjectionService(SeenRecoveryRepository repository, SeenMessageDecoder decoder, StringRedisTemplate redis) {
        this(repository,decoder,redis,(phase,record)->{});
    }
    SeenProjectionService(SeenRecoveryRepository repository, SeenMessageDecoder decoder, StringRedisTemplate redis, Probe probe) {
        this.repository=repository;this.decoder=decoder;this.redis=redis;this.probe=probe;
    }
    /** 每次poll单记录，原坐标终态重投复用PG结果，不能据此推断Redis永不丢失。 */
    public Outcome consume(String stream,int partition,long offset,byte[] key,byte[] value) {
        if(repository.blocked(stream)) return Outcome.PAUSED;
        var decoded=decoder.decode(key,value);
        var record=repository.register(stream,partition,offset,key,value,decoded);
        if(record.activeCommandId()!=null) return Outcome.PAUSED;
        if(record.acknowledgeable()) { probe.at(Phase.CLASSIFIED,record); return Outcome.ACK; }
        if(SeenRecord.State.PAUSED.name().equals(record.status())) return Outcome.PAUSED;
        var claim=repository.claim(record);
        if(claim==null) return repository.blocked(stream)?Outcome.PAUSED:Outcome.WAIT;
        io.micrometer.core.instrument.Metrics.counter("recsys.seen.attempts","stream",stream,"path","consume").increment();
        probe.at(Phase.CLAIMED,claim);
        try { applyRedis(decoded); }
        catch (SeenRecord.Failure failed) {
            if(failed.code()!=DEPENDENCY_UNAVAILABLE) throw failed;
            if(!repository.failed(claim,delay(claim.attempts()))) throw new SeenRecord.Failure(ACTIVE_LEASE);
            return claim.attempts()==3?Outcome.PAUSED:Outcome.WAIT;
        }
        probe.at(Phase.REDIS_APPLIED,claim);
        if(!repository.applied(claim)) throw new SeenRecord.Failure(ACTIVE_LEASE);
        probe.at(Phase.PG_APPLIED,claim);
        return Outcome.ACK;
    }
    /** 正反馈唯一真实效果；返回0表示此前已存在，也满足本次SADD成功条件。 */
    public void applyRedis(SeenMessageDecoder.Decoded decoded) {
        if(!SeenRecord.Classification.POSITIVE.name().equals(decoded.classification())) throw new SeenRecord.Failure(INVALID_COMMAND);
        try {
            Long added=redis.opsForSet().add(RedisKeys.seenItems(decoded.userId()),Long.toString(decoded.itemId()));
            if(added==null || (added!=0 && added!=1)) throw new SeenRecord.Failure(DEPENDENCY_UNAVAILABLE);
        } catch(DataAccessException dependency) { throw new SeenRecord.Failure(DEPENDENCY_UNAVAILABLE); }
    }
    static long delay(int attempts) { return (attempts==1?1000L:2000L)+ThreadLocalRandom.current().nextLong(251); }
}
