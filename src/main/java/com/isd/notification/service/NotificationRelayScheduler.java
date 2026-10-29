package com.isd.notification.service;

import java.util.List;

import com.isd.notification.entity.OutboxEvent;
import com.isd.notification.mapper.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发件箱投递调度器：定时把 PENDING 事件转成站内信。
 *
 * 多实例同时跑没有问题：两个实例可能取到同一批事件，但 notification 的
 * uk_event_user(event_id, user_id) 会把重复插入挡下来（见 NotificationRelayService）。
 * 代价是少量的重复劳动，换来的是不需要分布式锁、不会因为锁过期而丢事件。
 *
 * 每次 tick 只取一批，且取批语句 ORDER BY id，保证先产生的事件先投递；
 * 失败的事件留在 PENDING 等下一轮重试，attempts 达到上限后转 FAILED，
 * 不会出现"一条毒消息永远占据队头"。
 */
@Component
public class NotificationRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationRelayScheduler.class);
    /** 与 V5 迁移里 last_error 的列长度保持一致 */
    private static final int ERROR_MAX_LENGTH = 255;

    private final OutboxEventMapper outboxEventMapper;
    private final NotificationRelayService relayService;
    private final int batchSize;
    private final int maxAttempts;

    public NotificationRelayScheduler(OutboxEventMapper outboxEventMapper,
                                      NotificationRelayService relayService,
                                      @Value("${isd.notification.relay.batch-size:100}") int batchSize,
                                      @Value("${isd.notification.relay.max-attempts:5}") int maxAttempts) {
        this.outboxEventMapper = outboxEventMapper;
        this.relayService = relayService;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /**
     * fixedDelay 而不是 fixedRate：上一轮没跑完就不该叠下一轮，
     * 否则库里积压时会把线程和数据库连接一起压垮。
     */
    @Scheduled(fixedDelayString = "${isd.notification.relay.interval-ms:5000}",
            initialDelayString = "${isd.notification.relay.initial-delay-ms:10000}")
    public void relayPendingEvents() {
        List<OutboxEvent> events = outboxEventMapper.findPending(batchSize);
        if (events.isEmpty()) {
            return;
        }

        int succeeded = 0;
        int failed = 0;
        for (OutboxEvent event : events) {
            try {
                relayService.deliver(event);
                succeeded++;
            } catch (Exception ex) {
                failed++;
                markFailed(event, ex);
            }
        }
        log.info("站内信投递: 批次={}, 成功={}, 失败={}", events.size(), succeeded, failed);
    }

    /**
     * 失败计数交给一条 UPDATE 完成（见 OutboxEventMapper#markFailed），
     * 这里只负责把异常信息压到列宽内——完整堆栈进日志，库里只留一句可读原因。
     */
    private void markFailed(OutboxEvent event, Exception ex) {
        String message = failureMessage(ex);
        outboxEventMapper.markFailed(event.getId(), message, maxAttempts);
        // event 里是 UPDATE 之前的计数，+1 才是本次失败后的值；
        // 直接打旧值会让人误以为 attempts 没累加（排查时很容易被带偏）
        int attempts = event.getAttempts() == null ? 1 : event.getAttempts() + 1;
        log.warn("站内信投递失败: eventId={}, type={}, attempts={}, reason={}",
                event.getId(), event.getEventType(), attempts, message, ex);
    }

    /**
     * 只取最内层消息会丢上下文（Jackson 只说"Unexpected character"，看不出是哪个环节）；
     * 只取最外层又常常是"MyBatis 包装语句"，同样没信息量。两层都留：外层给场景，内层给原因。
     */
    private String failureMessage(Exception ex) {
        String outer = ex.getMessage();
        String root = rootMessage(ex);
        if (outer == null || outer.isBlank()) {
            return truncate(root, ERROR_MAX_LENGTH);
        }
        if (root == null || root.isBlank() || outer.contains(root)) {
            return truncate(outer, ERROR_MAX_LENGTH);
        }
        return truncate(outer + ": " + root, ERROR_MAX_LENGTH);
    }

    /** 最内层异常的消息；连消息都没有时退化成类名，至少能看出是哪种错误 */
    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
