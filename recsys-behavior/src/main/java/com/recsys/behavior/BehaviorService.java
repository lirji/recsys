package com.recsys.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.ActionType;
import com.recsys.common.dto.BehaviorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * 行为落库/投递服务(Track E · E1)。
 *
 * <p>反馈闭环的数据入口:在线埋点 → user_behavior 表(离线 ItemCF/热度/用户向量的输入)。
 *
 * <p>两种落地方式由开关 {@code recsys.behavior.use-kafka} 控制:
 * <ul>
 *   <li>false(默认,v1):直接 INSERT user_behavior,不依赖 Kafka,本地最省心;</li>
 *   <li>true:投递 Kafka(后续由消费者落库/进流式特征)。Kafka 未就绪时自动降级入库,不丢数据。</li>
 * </ul>
 *
 * <p>action 一律以大写({@link ActionType#name()})入库,与 I2iRecaller 的
 * {@code action IN ('CLICK','LIKE','PLAY','RATING')} 查询口径对齐。
 */
@Service
public class BehaviorService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorService.class);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${recsys.behavior.use-kafka:false}")
    private boolean useKafka;

    @Value("${recsys.behavior.topic:behavior-events}")
    private String topic;

    public BehaviorService(JdbcTemplate jdbc,
                           ObjectProvider<KafkaTemplate<String, String>> kafkaProvider) {
        this.jdbc = jdbc;
        this.kafkaProvider = kafkaProvider;
    }

    /** 接收一条行为事件:服务端补全时间戳后落地。 */
    public void record(BehaviorEvent raw) {
        BehaviorEvent ev = normalize(raw);
        if (useKafka) {
            KafkaTemplate<String, String> kafka = kafkaProvider.getIfAvailable();
            if (kafka != null) {
                sendKafka(kafka, ev);
                return;
            }
            log.warn("use-kafka=true 但 KafkaTemplate 不可用,降级直接入库");
        }
        insert(ev);
    }

    private BehaviorEvent normalize(BehaviorEvent ev) {
        long ts = ev.ts() > 0 ? ev.ts() : System.currentTimeMillis();
        return new BehaviorEvent(ev.userId(), ev.itemId(), ev.action(),
                ev.value(), ev.scene(), ev.bucket(), ts);
    }

    private void insert(BehaviorEvent ev) {
        // action 存大写枚举名,与召回侧查询口径一致
        jdbc.update(
                "INSERT INTO user_behavior(user_id,item_id,action,value,scene,bucket,ts) " +
                "VALUES(?,?,?,?,?,?,?)",
                ev.userId(), ev.itemId(),
                ev.action() == null ? null : ev.action().name(),
                ev.value(), ev.scene(), ev.bucket(), new Timestamp(ev.ts()));
    }

    private void sendKafka(KafkaTemplate<String, String> kafka, BehaviorEvent ev) {
        try {
            // 按 userId 分区,保证同一用户行为有序
            kafka.send(topic, String.valueOf(ev.userId()), mapper.writeValueAsString(ev))
                    .whenComplete((r, e) -> {
                        if (e != null) {
                            log.warn("Kafka 投递失败,降级入库: {}", e.getMessage());
                            insert(ev);
                        }
                    });
        } catch (Exception e) {
            log.warn("序列化/投递异常,降级入库: {}", e.getMessage());
            insert(ev);
        }
    }
}
