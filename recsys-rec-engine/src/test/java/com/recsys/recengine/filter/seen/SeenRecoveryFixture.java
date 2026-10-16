package com.recsys.recengine.filter.seen;

import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.recengine.filter.SeenItemsConsumer;
import com.recsys.recengine.persistence.mapper.primary.SeenRecoveryMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import com.github.dockerjava.api.command.InspectContainerResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** 仅独占PG schema/Kafka topic/Redis端口；真实产品配置和listener，无测试Sink。 */
class SeenRecoveryFixture {
    final DriverManagerDataSource ds;
    final JdbcTemplate jdbc;
    final SeenRecoveryRepository repo;
    final SeenRecoveryMapper mapper;
    final SeenMessageDecoder decoder=new SeenMessageDecoder();
    SeenRecoveryFixture(PostgreSQLContainer<?> pg) throws Exception {
        this(schema(pg));migrate();repo.verifySchema();
    }
    SeenRecoveryFixture(DriverManagerDataSource source) throws Exception {
        ds=source;jdbc=new JdbcTemplate(ds);
        var sessions=new SqlSessionTemplate(SpringSqlSessions.create(ds,"classpath*:mapper/recengine/primary/SeenRecoveryMapper.xml"));
        mapper=sessions.getMapper(SeenRecoveryMapper.class);repo=new SeenRecoveryRepository(sessions,new DataSourceTransactionManager(ds));
    }
    static DriverManagerDataSource schema(PostgreSQLContainer<?> pg) {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
        String name="seen_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE SCHEMA "+name);
        return new DriverManagerDataSource(pg.getJdbcUrl()+(pg.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+name,pg.getUsername(),pg.getPassword());
    }
    void migrate() throws Exception {
        try(var in=new ClassPathResource(SeenRecoveryRepository.MIGRATION).getInputStream();var c=ds.getConnection();var s=c.createStatement()) {
            s.execute("SET recsys.migration_sha256='"+SeenRecoveryRepository.migrationSha256()+"'");s.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    static String topic() {return "behavior-events.seen-"+UUID.randomUUID();}
    static String group() {return "seen-test-"+UUID.randomUUID();}
    static KafkaProperties kafka(String bootstrap) {var p=new KafkaProperties();p.setBootstrapServers(List.of(bootstrap));return p;}
    static Admin admin(String bootstrap) {return Admin.create(Map.of("bootstrap.servers",bootstrap,"default.api.timeout.ms",5000,"request.timeout.ms",5000,"retries",3));}
    static void topic(String bootstrap,String topic,int partitions) throws Exception {
        try(var a=admin(bootstrap)){a.createTopics(List.of(new NewTopic(topic,partitions,(short)1).configs(Map.of("cleanup.policy","delete","retention.ms","-1","max.message.bytes","1048576")))).all().get(10,TimeUnit.SECONDS);}
    }
    static void send(String bootstrap,String topic,int partition,byte[] key,byte[] value) throws Exception {
        try(var p=new KafkaProducer<byte[],byte[]>(Map.of("bootstrap.servers",bootstrap,"key.serializer",ByteArraySerializer.class,"value.serializer",ByteArraySerializer.class,"acks","all","max.block.ms",5000))) {
            p.send(new ProducerRecord<>(topic,partition,key,value)).get(10,TimeUnit.SECONDS);
        }
    }
    static byte[] positive(long user,long item) {return ("{\"userId\":"+user+",\"itemId\":"+item+",\"action\":\"CLICK\"}").getBytes(StandardCharsets.UTF_8);}
    private static Admin observer;
    private static String observerBootstrap;
    static synchronized void closeObserver() {if(observer!=null)observer.close(Duration.ofSeconds(2));observer=null;observerBootstrap=null;}
    static synchronized Long offset(String bootstrap,String group,String topic,int partition) {
        if(observer==null || !bootstrap.equals(observerBootstrap)){closeObserver();observer=admin(bootstrap);observerBootstrap=bootstrap;}
        try {var o=observer.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).get(new TopicPartition(topic,partition));return o==null?null:o.offset();}
        catch(Exception e){throw new AssertionError(e);}
    }
    SeenRecord at(int partition,long offset) {return mapper.coordinate("LEGACY",partition,offset);}
    static void await(BooleanSupplier condition,int seconds) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(!condition.getAsBoolean()){assertTrue(System.nanoTime()<deadline,"等待真实状态超时");Thread.sleep(50);}
    }
    static LettuceConnectionFactory redisFactory(String host,int port) {
        var config=LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(400)).shutdownTimeout(Duration.ofMillis(100))
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(400)).build()).build()).build();
        var factory=new LettuceConnectionFactory(new RedisStandaloneConfiguration(host,port),config);factory.afterPropertiesSet();factory.start();return factory;
    }
    Runtime runtime(String bootstrap,String topic,String group,String host,int port) {return new Runtime(bootstrap,topic,group,host,port,null,null);}
    class Runtime implements AutoCloseable {
        final LettuceConnectionFactory redisFactory;final StringRedisTemplate redis;final SeenOffsetInitializer offsets;
        final SeenProjectionService projection;final SeenRecoveryService recovery;final SeenConsumerErrorHandler errors;final SeenListenerControl listener;
        Runtime(String bootstrap,String topic,String group,String host,int port,SeenProjectionService.Probe probe,String fault) {
            redisFactory=redisFactory(host,port);redis=new StringRedisTemplate(redisFactory);
            var props=SeenConsumerConfiguration.consumerProperties(kafka(bootstrap));
            offsets=new SeenOffsetInitializer(repo,admin(bootstrap),props,"LEGACY",topic,group) {
                @Override protected void afterOffsets(SeenRecoveryMapper.Command claim) {if("INIT".equals(fault))java.lang.Runtime.getRuntime().halt(73);}
            };
            projection=probe==null?new SeenProjectionService(repo,decoder,redis) {
                @Override public void applyRedis(SeenMessageDecoder.Decoded d) {super.applyRedis(d);if("RECOVERY".equals(fault))java.lang.Runtime.getRuntime().halt(73);}
            }:new SeenProjectionService(repo,decoder,redis,probe);
            recovery=new SeenRecoveryService(repo,offsets,projection,decoder,true);errors=new SeenConsumerErrorHandler(repo);
            var beans=new DefaultListableBeanFactory();beans.registerSingleton("projection",projection);
            var consumer=new SeenItemsConsumer(beans.getBeanProvider(SeenProjectionService.class),topic);
            listener=new SeenListenerControl(SeenConsumerConfiguration.factory(props,errors),consumer,repo,offsets,topic,group,false);
        }
        void initialize() {var id=UUID.randomUUID();recovery.initialize(id,SeenOffsetInitializer.Mode.EARLIEST_RETAINED,"test-owner","独占初始化");assertEquals("SUCCEEDED",recovery.advance(id).status());}
        @Override public void close() {listener.stop();errors.close();offsets.close();redisFactory.destroy();}
    }
    static class KafkaFixture extends GenericContainer<KafkaFixture> {
        KafkaFixture() {
            super("apache/kafka:3.7.1");withExposedPorts(19092);
            withEnv(Map.of("KAFKA_NODE_ID","1","KAFKA_PROCESS_ROLES","broker,controller","KAFKA_CONTROLLER_QUORUM_VOTERS","1@localhost:9093",
                    "KAFKA_LISTENERS","INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:19092",
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP","INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT",
                    "KAFKA_INTER_BROKER_LISTENER_NAME","INTERNAL","KAFKA_CONTROLLER_LISTENER_NAMES","CONTROLLER",
                    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR","1","KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS","0","KAFKA_AUTO_CREATE_TOPICS_ENABLE","false"));
            withCommand("/bin/bash","-c","while [ ! -f /tmp/seen-kafka-start ]; do sleep 0.1; done; . /tmp/seen-kafka-start; exec /etc/kafka/docker/run");
            waitingFor(Wait.forLogMessage(".*Kafka Server started.*",1).withStartupTimeout(Duration.ofSeconds(90)));
        }
        @Override protected void containerIsStarting(InspectContainerResponse info) {copyFileToContainer(Transferable.of("export KAFKA_ADVERTISED_LISTENERS='INTERNAL://localhost:9092,HOST://"+bootstrap()+"'\n",0644),"/tmp/seen-kafka-start");}
        String bootstrap(){return getHost()+":"+getMappedPort(19092);}
    }
}
