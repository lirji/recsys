package com.recsys.behavior.exposure;

import com.recsys.common.exposure.NaturalQualification;
import com.recsys.common.exposure.NaturalQualificationToken;
import com.recsys.common.exposure.NaturalReceipt.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 接受自然曝光的本地事务边界；不依赖 ad/engine 数据库，也不在事务内做外部调用。 */
public class NaturalReceiptService {
    private final NaturalReceiptRepository repository;
    private final NaturalQualificationToken tokens;
    private final NaturalQualification.Policy policy;
    private final TransactionTemplate transactions;
    private final boolean admissionEnabled;

    /** 显式固定独立短事务，不能无意加入宿主长事务并提前向终端返回成功。 */
    public NaturalReceiptService(NaturalReceiptRepository repository, NaturalQualificationToken tokens,
            NaturalQualification.Policy policy, DataSourceTransactionManager manager, boolean admissionEnabled) {
        if (manager.getDataSource() != repository.dataSource()) throw new IllegalArgumentException("自然事实与事务必须同一 behavior 数据源");
        this.repository = repository; this.tokens = tokens; this.policy = Objects.requireNonNull(policy); this.admissionEnabled = admissionEnabled;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }
    /** 返回只发生在事务提交后；相同键的历史结果不受开关/密钥/政策轮换影响。 */
    public Result accept(Command command) {
        var original = repository.original(command);
        if (original.isPresent()) return original.get();
        if (!admissionEnabled) throw new IllegalStateException("新自然回执已暂停，原结果仍可查询");
        var q = tokens.verify(command.qualificationToken());
        if (!command.subject().equals(q.subject())) throw new AccessDenied();
        if (!policy.equals(q.policy())) throw new IllegalArgumentException("自然资格政策未获接收端配置认可");
        return transactions.execute(tx -> {
            repository.boundTransaction();
            if (!repository.claim(command, q.qualificationId())) return repository.original(command).orElseThrow();
            repository.lockQualification(q);
            var now = repository.databaseNow();
            var accepted = repository.accepted(q.qualificationId(), command.receiptId(), now);
            if (accepted.isPresent()) return repository.complete(accepted.get());
            Status status = !now.isBefore(q.expiresAt()) ? Status.EXPIRED
                    : command.meetsPolicy(q, now) ? Status.ACCEPTED : Status.POLICY_NOT_MET;
            if (status != Status.ACCEPTED) {
                return repository.complete(new Result(command.receiptId(), q.qualificationId(), status, null, null, null, now));
            }
            repository.appendFact(q, command.receiptId(), now);
            repository.appendOutbox(Event.accepted(q, command.receiptId(), now));
            return repository.complete(new Result(command.receiptId(), q.qualificationId(), status, q.qualificationId(), command.receiptId(), now, now));
        });
    }
    /** 查无结果只表示尚未观察到，不允许调用方据此换幂等 ID。 */
    public Optional<Result> find(UUID id, String subject) { return repository.find(Objects.requireNonNull(id), subject); }
}
