package com.recsys.behavior.events;

import com.recsys.behavior.persistence.mapper.primary.BehaviorEventMapper;
import com.recsys.behavior.persistence.mapper.primary.BehaviorHistoryMapper;
import com.recsys.behavior.persistence.mapper.primary.BehaviorHistoryMapper.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 显式、受信任的历史恢复入口；无自动装配、扫描或后台线程，不能通过请求重建未知业务事实。 */
public class BehaviorHistoryRepository {
    public static final String MIGRATION = "db/behavior-events/V002__behavior_history_capture.sql";
    public static final int MAX_IDS = 100;
    public static final int ADVANCE_LIMIT = 10;
    /** 固定持久化码与迁移约束对应，不采用序号。 */
    public enum CommandState { OPEN, COMPLETE, COMPLETED_WITH_ISSUES, CANCELLED }
    public enum ItemState { WAITING_SOURCE, CAPTURED, ALREADY_CAPTURED, INVALID_SOURCE, SOURCE_CHANGED }
    public enum CancelResult { CANCELLED, ALREADY_TERMINAL }
    /** 内部恢复失败闭合分类；异常不带数据库原文或原始cause。 */
    public enum Error { INVALID_COMMAND, COMMAND_CONFLICT, NOT_FOUND, SOURCE_MISMATCH, MIGRATION_INVALID, PERSISTENCE_FAILURE }
    public static final class Failure extends IllegalStateException {
        private final Error code;
        private Failure(Error code) { super("历史恢复操作未完成: " + code.name()); this.code = code; }
        public Error code() { return code; }
    }
    /** 捕获结果固定，deliveryCounts为查询时真实发送状态；取消未处理数仍属于waiting，不算覆盖。 */
    public record Report(Command command, List<Item> items, Map<String, Long> resultCounts,
                         Map<String, Long> deliveryCounts, long covered, long cancelledUnprocessed) { }

    private final BehaviorHistoryMapper mapper;
    private final BehaviorEventMapper eventMapper;
    private final BehaviorEventRepository events;
    private final TransactionTemplate transactions;

    /** 仅受控调用方显式创建；先拒绝旧发布并存，再校验V001/V002，绝不自动迁移。 */
    public BehaviorHistoryRepository(SqlSessionTemplate sessions, DataSourceTransactionManager manager, Environment environment) {
        Objects.requireNonNull(environment);
        if (environment.getProperty("recsys.behavior.use-kafka", Boolean.class, false)
                || environment.getProperty("recsys.behavior.publish-events", Boolean.class, false)) {
            throw new Failure(Error.INVALID_COMMAND);
        }
        events = new BehaviorEventRepository(sessions, manager);
        mapper = sessions.getMapper(BehaviorHistoryMapper.class);
        eventMapper = sessions.getMapper(BehaviorEventMapper.class);
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
        events.verifySchema();
        transaction(() -> {
            if (!migrationSha256().equals(DataAccessUtils.requiredSingleResult(eventMapper.migrationHashes(2)))) {
                throw new Failure(Error.MIGRATION_INVALID);
            }
            mapper.verifyCommands(); mapper.verifyItems(); mapper.verifyCancellations();
            return null;
        });
    }

    /** 迁移工具与启动检查读取同一打包脚本。 */
    public static String migrationSha256() {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new Failure(Error.MIGRATION_INVALID);
        }
    }

    /** 只登记显式清单；重复ID输入拒绝，排列不同但集合相同的精确命令复用原任务。 */
    public Report captureHistory(UUID commandId, UUID sourceId, List<Long> behaviorIds, String actor, String reason) {
        requireId(commandId); requireId(sourceId); requireAudit(actor, reason);
        if (behaviorIds == null || behaviorIds.isEmpty() || behaviorIds.size() > MAX_IDS
                || behaviorIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(behaviorIds).size() != behaviorIds.size()) throw new Failure(Error.INVALID_COMMAND);
        List<Long> ids = behaviorIds.stream().sorted().toList();
        String manifest = ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        return transaction(() -> {
            if (!sourceId.equals(eventMapper.sourceId())) throw new Failure(Error.SOURCE_MISMATCH);
            int inserted = mapper.insertCommand(commandId, sourceId, manifest, ids.size(), actor, reason);
            Command command = requireCommand(commandId);
            if (!command.sourceId().equals(sourceId) || !command.manifest().equals(manifest)
                    || !command.actor().equals(actor) || !command.reason().equals(reason)) throw new Failure(Error.COMMAND_CONFLICT);
            if (inserted == 1 && mapper.insertItems(commandId) != ids.size()) throw new Failure(Error.PERSISTENCE_FAILURE);
            return reportLocked(command);
        });
    }

    /** 每项独立提交，最多10个不同ID；进程退出只回滚正在处理项，之前的持久结果可继续。 */
    public Report advanceHistory(UUID commandId) {
        requireId(commandId);
        var visited = new ArrayList<Long>(ADVANCE_LIMIT);
        for (int i = 0; i < ADVANCE_LIMIT && !Thread.currentThread().isInterrupted(); i++) {
            Long processed = transaction(() -> advanceOne(commandId, visited));
            if (processed == null) break;
            visited.add(processed);
        }
        return historyReport(commandId);
    }

    private Long advanceOne(UUID commandId, List<Long> visited) {
        Command command = requireCommand(commandId);
        if (!CommandState.OPEN.name().equals(command.status())) return null;
        Long id = mapper.nextItem(commandId, visited);
        if (id == null) return null;
        if (mapper.tick(commandId) != 1) throw new Failure(Error.PERSISTENCE_FAILURE);
        // command→item→源行→Outbox；源行锁后必须用下一条SQL看并发捕获赢家。
        Long source = mapper.lockSource(id);
        Existing original = mapper.existing(id);
        ItemState result;
        String eventId = null;
        if (original != null) {
            eventId = original.eventId();
            // 源行消失不消除已知非法快照；保留原隔离义务，不能把它计为无问题覆盖。
            result = source != null && original.sourceChanged() ? ItemState.SOURCE_CHANGED
                    : "INVALID_SOURCE".equals(original.error()) ? ItemState.INVALID_SOURCE : ItemState.ALREADY_CAPTURED;
        } else if (source == null) {
            result = ItemState.WAITING_SOURCE;
        } else {
            events.captureHistoryInTransaction(id);
            original = Objects.requireNonNull(mapper.existing(id));
            eventId = original.eventId();
            result = "INVALID_SOURCE".equals(original.error()) ? ItemState.INVALID_SOURCE : ItemState.CAPTURED;
        }
        String error = switch (result) {
            case WAITING_SOURCE -> "SOURCE_NOT_VISIBLE";
            case INVALID_SOURCE, SOURCE_CHANGED -> result.name();
            default -> null;
        };
        if (mapper.saveItem(commandId, id, result.name(), eventId, error) != 1) throw new Failure(Error.PERSISTENCE_FAILURE);
        mapper.complete(commandId);
        return id;
    }

    /** 固定清单结果和查询时的broker确认状态分开统计，不因CAPTURED或取消而谎称投递完成。 */
    public Report historyReport(UUID commandId) {
        requireId(commandId);
        return transaction(() -> reportLocked(requireCommand(commandId)));
    }

    private Report reportLocked(Command command) {
        List<Item> items = List.copyOf(mapper.items(command.commandId()));
        if (items.size() != command.itemCount()) throw new Failure(Error.PERSISTENCE_FAILURE);
        Map<String, Long> results = new LinkedHashMap<>(), delivery = new LinkedHashMap<>();
        for (ItemState value : ItemState.values()) results.put(value.name(), 0L);
        delivery.put("NO_EVENT", 0L);
        for (BehaviorEventRepository.State value : BehaviorEventRepository.State.values()) delivery.put(value.code(), 0L);
        for (Item item : items) {
            results.compute(item.status(), (key, count) -> Objects.requireNonNull(count) + 1);
            delivery.compute(item.deliveryStatus() == null ? "NO_EVENT" : item.deliveryStatus(),
                    (key, count) -> Objects.requireNonNull(count) + 1);
        }
        long covered = results.get(ItemState.CAPTURED.name()) + results.get(ItemState.ALREADY_CAPTURED.name());
        return new Report(command, items, Map.copyOf(results), Map.copyOf(delivery), covered,
                CommandState.CANCELLED.name().equals(command.status()) ? results.get(ItemState.WAITING_SOURCE.name()) : 0);
    }

    /** 独立取消ID审计与任务终态同事务；精确重试返回首次结果，绝不撤销已创建或发送事件。 */
    public Cancellation cancelHistory(UUID cancelId, UUID commandId, String actor, String reason) {
        requireId(cancelId); requireId(commandId); requireAudit(actor, reason);
        return transaction(() -> {
            Cancellation original = mapper.cancellation(cancelId);
            if (original != null) return originalCancellation(original, commandId, actor, reason);
            Command command = requireCommand(commandId);
            original = mapper.cancellation(cancelId);
            if (original != null) return originalCancellation(original, commandId, actor, reason);
            boolean open = CommandState.OPEN.name().equals(command.status());
            String result = (open ? CancelResult.CANCELLED : CancelResult.ALREADY_TERMINAL).name();
            if (mapper.insertCancellation(cancelId, commandId, actor, reason, command.status(), result) == 0) {
                return originalCancellation(Objects.requireNonNull(mapper.cancellation(cancelId)), commandId, actor, reason);
            }
            if (open && mapper.cancel(commandId) != 1) throw new Failure(Error.PERSISTENCE_FAILURE);
            return Objects.requireNonNull(mapper.cancellation(cancelId));
        });
    }

    private Cancellation originalCancellation(Cancellation original, UUID commandId, String actor, String reason) {
        if (!original.commandId().equals(commandId) || !original.actor().equals(actor) || !original.reason().equals(reason)) {
            throw new Failure(Error.COMMAND_CONFLICT);
        }
        return original;
    }
    private Command requireCommand(UUID id) {
        Command command = mapper.lockCommand(id);
        if (command == null) throw new Failure(Error.NOT_FOUND);
        return command;
    }
    private static void requireId(UUID id) { if (id == null) throw new Failure(Error.INVALID_COMMAND); }
    private static void requireAudit(String actor, String reason) {
        if (actor == null || actor.isBlank() || actor.length() > 200
                || reason == null || reason.isBlank() || reason.length() > 1000) throw new Failure(Error.INVALID_COMMAND);
    }
    private <T> T transaction(Supplier<T> work) {
        try {
            return transactions.execute(status -> {
                eventMapper.boundLocks(); eventMapper.boundStatements();
                return work.get();
            });
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException failure) {
            // 约束异常可能携带源行或操作者原文；不将原始cause交给普通日志。
            throw new Failure(Error.PERSISTENCE_FAILURE);
        }
    }
}
