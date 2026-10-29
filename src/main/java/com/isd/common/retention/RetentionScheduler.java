package com.isd.common.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据保留任务的触发器，只负责"什么时候跑"和兜底日志；怎么删在 {@link RetentionService}。
 *
 * 时间选择：默认每天 03:30，避开使用高峰。删的是三天前的历史数据，几点跑对业务都没影响，
 * 但放在夜间能让 DELETE 与白天的报表/检索查询错开。
 *
 * 为什么加 @ConditionalOnProperty：本地排查数据问题时经常需要"今天先别删"，
 * 一个配置就能关掉，不用改代码也不会留下半开的开关。
 */
@Component
@ConditionalOnProperty(name = "isd.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionService retentionService;

    public RetentionScheduler(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(cron = "${isd.retention.cron:0 30 3 * * *}")
    public void runDailyCleanup() {
        long startNanos = System.nanoTime();
        try {
            RetentionService.CleanupResult result = retentionService.cleanupAll();
            log.info("数据清理完成: aiChatLog={}, outboxEvent={}, notification={}, total={}, cost={}ms",
                    result.aiChatLogs(), result.outboxEvents(), result.notifications(), result.total(),
                    (System.nanoTime() - startNanos) / 1_000_000);
        } catch (Exception ex) {
            // 任务级兜底：调度线程里抛出的异常会静默终止本次执行，至少留一条可检索的 ERROR
            log.error("数据清理任务异常终止", ex);
        }
    }
}
