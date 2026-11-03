package com.isd.ai.mapper;

import java.util.Optional;

import com.isd.ai.dto.AiMetricsResponse;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiFeedbackMapper {
    @Select("SELECT user_id FROM ai_chat_log WHERE id=#{id}")
    Optional<Long> owner(Long id);

    @Insert("""
            INSERT INTO ai_answer_feedback(answer_id,user_id,helpful,resolved,adopted)
            VALUES(#{id},#{userId},#{helpful},#{resolved},#{adopted})
            ON DUPLICATE KEY UPDATE
              helpful=COALESCE(VALUES(helpful),helpful),
              resolved=COALESCE(VALUES(resolved),resolved),
              adopted=COALESCE(VALUES(adopted),adopted),updated_at=CURRENT_TIMESTAMP
            """)
    int feedback(@Param("id") Long id, @Param("userId") Long userId,
                 @Param("helpful") Boolean helpful, @Param("resolved") Boolean resolved,
                 @Param("adopted") Boolean adopted);

    @Select("""
            SELECT (SELECT COUNT(*) FROM ai_chat_log) answers, COUNT(*) feedback_count,
                COALESCE(SUM(f.helpful=1),0) helpful_count, COALESCE(SUM(f.resolved=1),0) resolved_count,
                COALESCE(SUM(f.adopted=1),0) adopted_count,
                (SELECT COUNT(*) FROM ai_chat_log WHERE purpose IN ('DRAFT','ASSIST')) suggestion_count,
                COUNT(f.helpful) helpful_feedback_count,
                COALESCE(SUM(f.adopted=1 AND l.purpose IN ('DRAFT','ASSIST')),0) suggestion_adopted_count
            FROM ai_answer_feedback f JOIN ai_chat_log l ON l.id=f.answer_id
            WHERE f.helpful IS NOT NULL OR f.resolved IS NOT NULL OR f.adopted IS NOT NULL
            """)
    AiMetricsResponse metrics();

    @Update("UPDATE ai_chat_log SET source='FALLBACK',success=0 WHERE id=#{id} AND user_id=#{userId}")
    int markInvalid(@Param("id") Long id, @Param("userId") Long userId);

    @Update("""
            UPDATE ai_chat_log SET purpose=#{purpose}
            WHERE id=#{id} AND user_id=#{userId} AND #{purpose} IN ('DRAFT','ASSIST')
            """)
    int markSuggestion(@Param("id") Long id, @Param("userId") Long userId, @Param("purpose") String purpose);
}
