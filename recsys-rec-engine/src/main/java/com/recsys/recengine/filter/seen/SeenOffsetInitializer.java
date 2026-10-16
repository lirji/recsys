package com.recsys.recengine.filter.seen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.recengine.persistence.mapper.primary.SeenRecoveryMapper.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.recsys.recengine.filter.seen.SeenRecord.Code.*;

/** 初始化只在无活跃成员窗口作用于冻结新增位点；不重置已有组。 */
public class SeenOffsetInitializer implements AutoCloseable {
    public enum Mode { ADOPT_COMMITTED, EARLIEST_RETAINED, ENROLL_NEW_PARTITIONS }
    public record Position(int partition, long beginning, long end, Long committed, boolean enroll) { }
    public record Manifest(String mode, String stream, String clusterId, String topicId, String topic, String groupId, List<Position> positions) { }
    private final SeenRecoveryRepository repository;
    private final Admin admin;
    private final Map<String,Object> consumerProperties;
    private final ObjectMapper json=new ObjectMapper();
    private final String topic;
    private final String group;
    private final String stream;

    /** 绑定只能来自该流配置，不允许内部命令任意选择broker/topic/group。 */
    public SeenOffsetInitializer(SeenRecoveryRepository repository, Admin admin, Map<String,Object> consumerProperties,
                                 String stream,String topic,String group) {
        this.repository=repository;this.admin=admin;this.consumerProperties=Map.copyOf(consumerProperties);
        this.stream=stream;this.topic=topic;this.group=group;
    }
    /** 同ID先读冻结内容；不同ID在任何offset副作用前通过PG持久排他。 */
    public Command initialize(UUID id,Mode mode,String actor,String reason) {
        SeenRecoveryService.audit(id,actor,reason);
        var old=existingInitialization(id,mode,actor,reason);if(old!=null)return old;
        var snapshot=observe(true);
        // 首次读与观测间另一个调用可能已冻结或提交Kafka；先复用持久胜者再判断位点。
        old=existingInitialization(id,mode,actor,reason);if(old!=null)return old;
        var registered=repository.partitions(stream);
        var oldIds=new HashSet<Integer>();registered.forEach(p->oldIds.add(p.partitionId()));
        var binding=new Control(stream,snapshot.clusterId(),snapshot.topicId(),topic,group,null,null);
        var existing=repository.control(stream);if(existing!=null)SeenRecoveryRepository.sameBinding(existing,binding);
        if(mode!=Mode.ENROLL_NEW_PARTITIONS && !registered.isEmpty())throw new SeenRecord.Failure(NOT_ELIGIBLE);
        if(mode==Mode.ENROLL_NEW_PARTITIONS && registered.isEmpty())throw new SeenRecord.Failure(NOT_ELIGIBLE);
        var positions=new ArrayList<Position>();
        for(var p:snapshot.positions()) {
            boolean enrolled=oldIds.remove(p.partition());
            if(enrolled) {
                requireOffset(p);positions.add(new Position(p.partition(),p.beginning(),p.end(),p.committed(),false));
            } else {
                if(mode==Mode.ADOPT_COMMITTED) requireOffset(p);
                else if(p.committed()!=null)throw new SeenRecord.Failure(OFFSET_GAP);
                positions.add(new Position(p.partition(),p.beginning(),p.end(),p.committed(),true));
            }
        }
        if(!oldIds.isEmpty() || positions.stream().noneMatch(Position::enroll))throw new SeenRecord.Failure(OFFSET_GAP);
        var frozen=new Manifest(mode.name(),stream,snapshot.clusterId(),snapshot.topicId(),topic,group,List.copyOf(positions));
        var command=SeenRecoveryService.command(id,SeenRecord.CommandKind.INITIALIZE.name(),stream,null,write(frozen),actor,reason);
        return repository.initialize(binding,command,registered.stream().map(Partition::partitionId).toList());
    }
    private Command existingInitialization(UUID id,Mode mode,String actor,String reason) {
        var old=repository.rawCommand(id);
        if(old!=null) {
            var frozen=manifest(old);
            if(!old.kind().equals(SeenRecord.CommandKind.INITIALIZE.name()) || !frozen.mode().equals(mode.name()) || !old.actor().equals(actor) || !old.reason().equals(reason)
                    || !frozen.stream().equals(stream) || !frozen.topic().equals(topic) || !frozen.groupId().equals(group)) throw new SeenRecord.Failure(COMMAND_CONFLICT);
            return old;
        }
        var owner=repository.control(stream);
        if(owner!=null && owner.initializingCommandId()!=null) {
            if(!id.equals(owner.initializingCommandId()))throw new SeenRecord.Failure(COMMAND_CONFLICT);
            // control和命令原子写入；并发胜者在两次读取间提交时，重新读取原命令。
            return existingInitialization(id,mode,actor,reason);
        }
        return null;
    }
    /** 先查真实offset；仅补缺失新增项，不覆盖任何不等于冻结值的已写位点。 */
    void advance(Command claim) {
        Manifest frozen=manifest(claim);var actual=observe(true);sameIdentity(frozen,actual);
        if(frozen.positions().size()!=actual.positions().size())throw new SeenRecord.Failure(OFFSET_GAP);
        Map<TopicPartition,OffsetAndMetadata> missing=new HashMap<>();
        for(var p:frozen.positions()) {
            var now=position(actual,p.partition());
            long target=target(frozen,p);
            if(target<now.beginning() || target>now.end())throw new SeenRecord.Failure(OFFSET_GAP);
            if(!p.enroll() || frozen.mode().equals(Mode.ADOPT_COMMITTED.name())) {
                if(!Objects.equals(now.committed(),p.committed()))throw new SeenRecord.Failure(OFFSET_GAP);
            } else if(now.committed()==null)missing.put(new TopicPartition(topic,p.partition()),new OffsetAndMetadata(target));
            else if(now.committed()!=target)throw new SeenRecord.Failure(COMMAND_CONFLICT);
        }
        repository.initializationActive(claim);
        if(!missing.isEmpty()) call(()->admin.alterConsumerGroupOffsets(group,missing).all().get(5,TimeUnit.SECONDS));
        afterOffsets(claim);
        var readback=observe(true);sameIdentity(frozen,readback);
        if(frozen.positions().size()!=readback.positions().size())throw new SeenRecord.Failure(OFFSET_GAP);
        var bindings=new ArrayList<Partition>();
        for(var p:frozen.positions()) {
            var observed=position(readback,p.partition());long target=target(frozen,p);
            if(!Objects.equals(observed.committed(),target))throw new SeenRecord.Failure(OFFSET_GAP);
            if(p.enroll())bindings.add(new Partition(stream,p.partition(),frozen.clusterId(),frozen.topicId(),topic,group,target,claim.commandId(),target));
        }
        repository.initialized(claim,bindings);
    }
    // 测试通过真实Admin代理在调用后退出；产品不设置故障开关。
    protected void afterOffsets(Command claim) { }
    /** start/assignment/显式resume均重新核对真实身份及全部分区，不依赖lag近似。 */
    public void validate() {
        var snapshot=observe(false);var control=repository.control(stream);
        if(control==null)throw new SeenRecord.Failure(OFFSET_GAP);
        SeenRecoveryRepository.sameBinding(control,new Control(stream,snapshot.clusterId(),snapshot.topicId(),topic,group,null,null));
        if(control.initializingCommandId()!=null)throw new SeenRecord.Failure(ACTIVE_LEASE);
        var registered=repository.partitions(stream);
        if(registered.size()!=snapshot.positions().size())throw new SeenRecord.Failure(OFFSET_GAP);
        for(var p:snapshot.positions()) {
            if(registered.stream().noneMatch(r->r.partitionId()==p.partition()))throw new SeenRecord.Failure(OFFSET_GAP);
            requireOffset(p);
            if(repository.hasGap(stream,p.partition(),p.committed()))throw new SeenRecord.Failure(OFFSET_GAP);
        }
    }
    /** 不完整原文只按原坐标重取，超时/过期/相邻位点绝不冒充原消息。 */
    byte[][] fetch(SeenRecord record) {
        var snapshot=observe(false);
        if(!record.clusterId().equals(snapshot.clusterId()) || !record.topicId().equals(snapshot.topicId()))throw new SeenRecord.Failure(IDENTITY_CHANGED);
        var range=position(snapshot,record.partitionId());
        if(record.recordOffset()<range.beginning() || record.recordOffset()>=range.end())throw new SeenRecord.Failure(SOURCE_UNAVAILABLE);
        var properties=new HashMap<>(consumerProperties);properties.remove(ConsumerConfig.GROUP_ID_CONFIG);
        try(var consumer=new KafkaConsumer<byte[],byte[]>(properties)) {
            var tp=new TopicPartition(topic,record.partitionId());consumer.assign(List.of(tp));consumer.seek(tp,record.recordOffset());
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(System.nanoTime()<deadline) {
                for(var received:consumer.poll(Duration.ofMillis(200))) {
                    if(received.offset()!=record.recordOffset())throw new SeenRecord.Failure(SOURCE_UNAVAILABLE);
                    if(!SeenMessageDecoder.digest(received.key(),received.value()).equals(record.digest()))throw new SeenRecord.Failure(COORDINATE_CONFLICT);
                    return new byte[][]{received.key(),received.value()};
                }
            }
            throw new SeenRecord.Failure(SOURCE_UNAVAILABLE);
        } catch(SeenRecord.Failure known) {throw known;}
        catch(RuntimeException dependency) {throw new SeenRecord.Failure(DEPENDENCY_UNAVAILABLE);}
    }
    private Manifest observe(boolean inactive) {
        return call(()->{
            String stage="CLUSTER";
            try {
            String cluster=admin.describeCluster().clusterId().get(5,TimeUnit.SECONDS);
            stage="TOPIC";var desc=admin.describeTopics(List.of(topic)).allTopicNames().get(5,TimeUnit.SECONDS).get(topic);
            if(desc.partitions().isEmpty() || desc.partitions().size()>32 || desc.partitions().stream().anyMatch(p->p.partition()>=32))throw new SeenRecord.Failure(INVALID_COMMAND);
            var resource=new ConfigResource(ConfigResource.Type.TOPIC,topic);
            stage="CONFIG";var config=admin.describeConfigs(List.of(resource)).all().get(5,TimeUnit.SECONDS).get(resource);
            if(!"delete".equals(config.get("cleanup.policy").value()) || Integer.parseInt(config.get("max.message.bytes").value())>1048576)throw new SeenRecord.Failure(INVALID_COMMAND);
            if(inactive) {
                stage="GROUP";try {
                    var description=admin.describeConsumerGroups(List.of(group)).all().get(5,TimeUnit.SECONDS).get(group);
                    if(!description.members().isEmpty())throw new SeenRecord.Failure(ACTIVE_LEASE);
                } catch(java.util.concurrent.ExecutionException failure) {
                    if(!(failure.getCause() instanceof GroupIdNotFoundException))throw failure;
                }
            }
            var partitions=desc.partitions().stream().map(p->new TopicPartition(topic,p.partition())).toList();
            Map<TopicPartition,OffsetSpec> first=new HashMap<>(),last=new HashMap<>();
            partitions.forEach(p->{first.put(p,OffsetSpec.earliest());last.put(p,OffsetSpec.latest());});
            stage="RANGES";var begins=admin.listOffsets(first).all().get(5,TimeUnit.SECONDS);
            var ends=admin.listOffsets(last).all().get(5,TimeUnit.SECONDS);
            stage="COMMITTED";var offsets=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);
            if(offsets.keySet().stream().anyMatch(p->!p.topic().equals(topic)))throw new SeenRecord.Failure(IDENTITY_CHANGED);
            var ranges=partitions.stream().sorted(Comparator.comparingInt(TopicPartition::partition)).map(p->new Position(p.partition(),begins.get(p).offset(),ends.get(p).offset(),offsets.get(p)==null?null:offsets.get(p).offset(),false)).toList();
            return new Manifest("OBSERVE",stream,cluster,desc.topicId().toString(),topic,group,ranges);
            } catch(Exception failure) {org.slf4j.LoggerFactory.getLogger(SeenOffsetInitializer.class).warn("Seen只读核验失败 stage={}",stage);throw failure;}
        });
    }
    private long target(Manifest m,Position p) {return p.enroll() && !m.mode().equals(Mode.ADOPT_COMMITTED.name())?p.beginning():Objects.requireNonNull(p.committed());}
    private Position position(Manifest m,int id) {return m.positions().stream().filter(p->p.partition()==id).findFirst().orElseThrow(()->new SeenRecord.Failure(OFFSET_GAP));}
    private void requireOffset(Position p) {if(p.committed()==null || p.committed()<p.beginning() || p.committed()>p.end())throw new SeenRecord.Failure(OFFSET_GAP);}
    private void sameIdentity(Manifest a,Manifest b) {if(!a.clusterId().equals(b.clusterId()) || !a.topicId().equals(b.topicId()) || !a.topic().equals(b.topic()) || !a.groupId().equals(b.groupId()))throw new SeenRecord.Failure(IDENTITY_CHANGED);}
    private Manifest manifest(Command command) {try{return json.readValue(command.requestJson(),Manifest.class);}catch(com.fasterxml.jackson.core.JsonProcessingException bad){throw new SeenRecord.Failure(COMMAND_CONFLICT);}}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException bad){throw new SeenRecord.Failure(INTERNAL_ERROR);}}
    private interface Remote<T> {T get() throws Exception;}
    private <T> T call(Remote<T> remote) {try{return remote.get();}catch(SeenRecord.Failure known){throw known;}catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();org.slf4j.LoggerFactory.getLogger(SeenOffsetInitializer.class).warn("Seen依赖失败 type={} causeType={}",failure.getClass().getSimpleName(),failure.getCause()==null?"NONE":failure.getCause().getClass().getSimpleName());throw new SeenRecord.Failure(failure instanceof RuntimeException && !(failure instanceof org.apache.kafka.common.KafkaException)?INTERNAL_ERROR:DEPENDENCY_UNAVAILABLE);}}
    /** 有界关闭Admin，不等待无限网络排空。 */
    @Override public void close() {admin.close(Duration.ofSeconds(5));}
}
