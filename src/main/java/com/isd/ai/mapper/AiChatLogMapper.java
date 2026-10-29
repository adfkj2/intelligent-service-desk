package com.isd.ai.mapper;

import java.time.LocalDateTime;

import com.isd.ai.entity.AiChatLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

/**
 * AI 审计写入。只写不读：查询走数据库/BI 即可，
 * 应用里没有"看别人问过什么"的接口——那是隐私问题，不该由业务接口提供。
 */
@Mapper
public interface AiChatLogMapper {

    @Insert("""
            INSERT INTO ai_chat_log (trace_id, user_id, question, answer, source,
                                     kb_hits, tool_calls, duration_ms, success)
            VALUES (#{traceId}, #{userId}, #{question}, #{answer}, #{source},
                    #{kbHits}, #{toolCalls}, #{durationMs}, #{success})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiChatLog entry);

    /**
     * 保留策略：删除某个时间点之前的审计记录，单次最多 limit 行。
     *
     * 为什么带 LIMIT 而不是一条 DELETE 删干净：单条语句本身就是原子的，
     * 锁只在这一条语句执行期间持有；积压时靠次日多轮消化，
     * 不会因为一次清理把业务写入堵住（长事务锁表）。
     * 命中 V6 的 idx_ai_log_created，按时间范围扫描而不是全表。
     */
    @Delete("""
            DELETE FROM ai_chat_log
            WHERE created_at < #{cutoff}
            ORDER BY created_at
            LIMIT #{limit}
            """)
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
