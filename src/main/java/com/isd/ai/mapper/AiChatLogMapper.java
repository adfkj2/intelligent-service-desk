package com.isd.ai.mapper;

import com.isd.ai.entity.AiChatLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

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
}
