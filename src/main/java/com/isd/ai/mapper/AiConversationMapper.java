package com.isd.ai.mapper;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import com.isd.ai.dto.ConversationResponse;
import com.isd.ai.entity.AiConversation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiConversationMapper {
    @Insert("""
            INSERT INTO ai_conversation(id,user_id,ticket_id,purpose,access_role)
            VALUES(#{id},#{userId},#{ticketId},#{purpose},#{accessRole})
            """)
    int insert(AiConversation conversation);

    @Select("SELECT id,user_id,ticket_id,purpose,access_role,version FROM ai_conversation WHERE id=#{id} AND user_id=#{userId}")
    Optional<AiConversation> owned(@Param("id") String id, @Param("userId") Long userId);

    @Select("""
            SELECT role,content,answer_id,created_at FROM (
              SELECT id,role,content,answer_id,created_at FROM ai_conversation_message
              WHERE conversation_id=#{id} ORDER BY id DESC LIMIT 10
            ) recent ORDER BY id
            """)
    List<ConversationResponse.Message> messages(String id);

    @Update("""
            UPDATE ai_conversation SET version=version+1,updated_at=CURRENT_TIMESTAMP
            WHERE id=#{id} AND user_id=#{userId} AND version=#{version}
            """)
    int advance(AiConversation conversation);

    @Insert("""
            INSERT INTO ai_conversation_message(conversation_id,role,content,answer_id)
            VALUES(#{id},#{role},#{content},#{answerId})
            """)
    int append(@Param("id") String id, @Param("role") String role,
               @Param("content") String content, @Param("answerId") Long answerId);

    @Delete("""
            DELETE FROM ai_conversation_message WHERE conversation_id=#{id} AND id < (
                SELECT oldest FROM (
                    SELECT MIN(id) oldest FROM (
                        SELECT id FROM ai_conversation_message WHERE conversation_id=#{id} ORDER BY id DESC LIMIT 10
                    ) retained
                ) cutoff
            )
            """)
    int trim(String id);

    /** Messages are removed by the conversation foreign key's ON DELETE CASCADE. */
    @Delete("""
            DELETE FROM ai_conversation WHERE updated_at < #{cutoff}
            ORDER BY updated_at LIMIT #{limit}
            """)
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
