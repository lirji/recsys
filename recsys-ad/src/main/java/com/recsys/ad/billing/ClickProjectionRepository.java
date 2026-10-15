package com.recsys.ad.billing;

import com.recsys.ad.persistence.mapper.addb.ClickProjectionMapper;
import org.mybatis.spring.SqlSessionTemplate;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.UUID;
import com.recsys.common.ad.billing.CnyAmount;

/** 消费者自己的可重建投影，不连接权威账户/账本，不以版本水位丢弃独立金额。 */
public class ClickProjectionRepository {
    private final ClickProjectionMapper mapper;
    private final DataSource dataSource;
    /** 绑定实际会话资源，不能通过另传相同URL的DataSource绕过事务身份校验。 */
    public ClickProjectionRepository(SqlSessionTemplate sessions) {
        this.mapper=sessions.getMapper(ClickProjectionMapper.class);
        this.dataSource=sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }
    /** Inbox和汇总必须加入同一物理事务资源。 */
    public javax.sql.DataSource dataSource() { return dataSource; }

    /** Inbox 唯一键与汇总由调用方同一事务包围；同 ID 改负载必须失败。 */
    public boolean insert(ClickEventCodec.Validated value) {
        var e=value.event();
        int rows=mapper.insert(new ClickProjectionMapper.Fact(UUID.fromString(e.eventId()),UUID.fromString(e.qualificationId()),
                UUID.fromString(e.visibleReceiptId()),UUID.fromString(e.clickReceiptId()),Long.parseLong(e.advertiserId()),LocalDate.parse(e.budgetDate()),
                CnyAmount.parse(e.amount()),value.hash(),value.canonical()));
        if (rows==1) {
            // 两个列各自UNIQUE不能阻止VISIBLE复用别人的CLICK；共同索引并按UUID排序避免交叉领取死锁。
            int receipts=mapper.insertReceipts(UUID.fromString(e.visibleReceiptId()),UUID.fromString(e.clickReceiptId()),UUID.fromString(e.eventId()));
            if(receipts!=2) throw new IllegalStateException("点击事件回执身份跨种类冲突");
            return true;
        }
        // 同一事件并发可能先命中资格/回执唯一键，不能只指定 event_id 冲突目标。
        // 冲突后必须找到同 eventId 同负载，换 eventId 复用资格仍严格拒绝。
        var original=mapper.hashes(UUID.fromString(e.eventId()));
        if (original.size()!=1 || !value.hash().equals(original.getFirst())) {
            throw new IllegalStateException("账本事件身份或负载冲突");
        }
        return false;
    }

    /** 即使账户版本较小，只要是首次独立事实也累加；精度由 NUMERIC 保证。 */
    public void accumulate(ClickEventCodec.Validated value) {
        var e=value.event();
        if (mapper.accumulate(Long.parseLong(e.advertiserId()),LocalDate.parse(e.budgetDate()),CnyAmount.parse(e.amount()))!=1) {
            throw new IllegalStateException("投影汇总未保存");
        }
    }
}
