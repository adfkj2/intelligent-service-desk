package com.isd.ai.service;

import com.isd.ai.entity.AiChatLog;
import com.isd.ai.mapper.AiChatLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 审计写入。
 *
 * 唯一的硬要求：**审计失败不能影响回答**。审计是旁路，不是主流程；
 * 数据库抖动时让用户拿不到答案，比丢一条审计严重得多。所以这里吞异常 + WARN 日志，
 * 让 traceId 还能在应用日志里找到这次问答。
 *
 * 不加 @Transactional：单条 INSERT 本身就是原子的，套事务只会多占一个连接。
 */
@Service
public class AiChatAuditService {

    private static final Logger log = LoggerFactory.getLogger(AiChatAuditService.class);

    private final AiChatLogMapper mapper;

    public AiChatAuditService(AiChatLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(AiChatLog entry) {
        try {
            mapper.insert(entry);
        } catch (RuntimeException ex) {
            log.warn("AI 审计写入失败，traceId={}, 原因={}", entry.getTraceId(), ex.getMessage());
        }
    }
}
