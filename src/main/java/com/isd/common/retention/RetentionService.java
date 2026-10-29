package com.isd.common.retention;

import java.time.LocalDateTime;

import com.isd.ai.mapper.AiChatLogMapper;
import com.isd.common.metrics.IsdMetrics;
import com.isd.notification.mapper.NotificationMapper;
import com.isd.notification.mapper.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 数据保留策略：把"只增不减"的三张表按时间边界分批删掉。
 *
 * 三个取舍：
 * <ul>
 *   <li><b>每条 DELETE 带 LIMIT</b>：单条 DELETE 本身就是原子的，锁只在语句执行期间持有。
 *       一条 DELETE 删全量会形成长事务，把业务写入一起堵在行锁上；分批后积压也只是次日继续。</li>
 *   <li><b>不加 @Transactional</b>：批与批之间本来就不该是一个事务，否则又变回长事务；
 *       某一批失败时前面已删的数据保留（删除是幂等的，重跑即可），不影响正确性。</li>
 *   <li><b>单目标异常隔离</b>：一张表锁等待超时不该让另外两张表当天不清理。</li>
 * </ul>
 *
 * 只删"用户已经消费完"或"已经处理完"的数据：
 * 未读通知不删（用户可能还没看）、PENDING 事件不删（还要投递）、FAILED 事件不删（人工排查现场）。
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    public static final String TARGET_AI_CHAT_LOG = "ai_chat_log";
    public static final String TARGET_OUTBOX_EVENT = "outbox_event";
    public static final String TARGET_NOTIFICATION = "notification";

    private final AiChatLogMapper aiChatLogMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final NotificationMapper notificationMapper;
    private final IsdMetrics metrics;
    private final int batchSize;
    private final int maxBatches;
    private final int aiLogDays;
    private final int outboxSentDays;
    private final int notificationReadDays;

    public RetentionService(AiChatLogMapper aiChatLogMapper,
                            OutboxEventMapper outboxEventMapper,
                            NotificationMapper notificationMapper,
                            IsdMetrics metrics,
                            @Value("${isd.retention.batch-size:1000}") int batchSize,
                            @Value("${isd.retention.max-batches:50}") int maxBatches,
                            @Value("${isd.retention.ai-log-days:90}") int aiLogDays,
                            @Value("${isd.retention.outbox-sent-days:30}") int outboxSentDays,
                            @Value("${isd.retention.notification-read-days:90}") int notificationReadDays) {
        this.aiChatLogMapper = aiChatLogMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.notificationMapper = notificationMapper;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.aiLogDays = aiLogDays;
        this.outboxSentDays = outboxSentDays;
        this.notificationReadDays = notificationReadDays;
    }

    /**
     * 每轮清理的结果，同时用于日志与测试断言。
     * 用 record 而不是返回 void：测试要验证"哪张表被删了多少"，日志也要能对上。
     */
    public record CleanupResult(int aiChatLogs, int outboxEvents, int notifications) {
        public int total() {
            return aiChatLogs + outboxEvents + notifications;
        }
    }

    /**
     * 清理三个目标。时间边界在方法开始时统一算一次，保证同一轮三个目标口径一致
     * （否则跑得慢的时候，后面的目标会用到更晚的 now，边界悄悄漂移几秒）。
     */
    public CleanupResult cleanupAll() {
        LocalDateTime now = LocalDateTime.now();
        int aiChatLogs = cleanup(TARGET_AI_CHAT_LOG, cutoff(now, aiLogDays),
                (cutoff, limit) -> aiChatLogMapper.deleteBefore(cutoff, limit));
        int outboxEvents = cleanup(TARGET_OUTBOX_EVENT, cutoff(now, outboxSentDays),
                (cutoff, limit) -> outboxEventMapper.deleteSentBefore(cutoff, limit));
        int notifications = cleanup(TARGET_NOTIFICATION, cutoff(now, notificationReadDays),
                (cutoff, limit) -> notificationMapper.deleteReadBefore(cutoff, limit));
        return new CleanupResult(aiChatLogs, outboxEvents, notifications);
    }

    private int cleanup(String target, LocalDateTime cutoff, BatchDelete delete) {
        int deleted = 0;
        try {
            // 一次删满 batchSize 说明可能还有，继续下一批；不满就提前收工，省一次空查询
            for (int batch = 0; batch < maxBatches; batch++) {
                int affected = delete.delete(cutoff, batchSize);
                deleted += affected;
                if (affected < batchSize) {
                    break;
                }
            }
        } catch (Exception ex) {
            // 只 WARN 不上抛：一张表清理失败不应该让另外两张表当天不清理。
            // 异常信息里不放 SQL 参数（cutoff 已经单独打印），避免日志被数据淹没。
            log.warn("数据清理失败，跳过该目标: target={}, cutoff={}, deletedBeforeFailure={}",
                    target, cutoff, deleted, ex);
        }
        metrics.recordRetentionDeleted(target, deleted);
        return deleted;
    }

    private LocalDateTime cutoff(LocalDateTime now, int days) {
        return now.minusDays(days);
    }

    @FunctionalInterface
    private interface BatchDelete {
        int delete(LocalDateTime cutoff, int limit);
    }
}
