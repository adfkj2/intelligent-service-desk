package com.isd.ticket.mapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.isd.ticket.dto.TicketReplyResponse;
import com.isd.ticket.entity.TicketAttachment;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TicketCollaborationMapper {
    @Insert("""
            INSERT INTO ticket_reply(ticket_id,author_id,content,visibility)
            VALUES(#{ticketId},#{authorId},#{content},#{visibility})
            """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertReply(Map<String,Object> value);

    @Select("""
            <script>
            SELECT r.id,r.ticket_id,r.author_id,
                COALESCE(NULLIF(u.full_name,''),u.username,CONCAT('用户 ',r.author_id)) author_name,
                r.content,r.visibility,r.created_at
            FROM ticket_reply r LEFT JOIN sys_user u ON u.id=r.author_id
            WHERE r.ticket_id=#{ticketId}
            <if test="publicOnly">AND r.visibility='PUBLIC'</if>
            ORDER BY r.id DESC
            LIMIT 1000
            </script>
            """)
    List<TicketReplyResponse> replies(@Param("ticketId") Long id,@Param("publicOnly") boolean publicOnly);

    @Select("""
            SELECT r.id,r.ticket_id,r.author_id,
                COALESCE(NULLIF(u.full_name,''),u.username,CONCAT('用户 ',r.author_id)) author_name,
                r.content,r.visibility,r.created_at
            FROM ticket_reply r LEFT JOIN sys_user u ON u.id=r.author_id WHERE r.id=#{id}
            """)
    TicketReplyResponse replyById(@Param("id") Long id);

    @Insert("""
            INSERT INTO ticket_attachment(ticket_id,uploader_id,file_name,content_type,size,visibility,data)
            VALUES(#{ticketId},#{uploaderId},#{fileName},#{contentType},#{size},#{visibility},#{data})
            """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertAttachment(TicketAttachment value);

    @Select("""
            <script>
            SELECT f.id,f.ticket_id,f.uploader_id,
                COALESCE(NULLIF(u.full_name,''),u.username,CONCAT('用户 ',f.uploader_id)) uploader_name,
                f.file_name,f.content_type,f.size,f.visibility,f.created_at
            FROM ticket_attachment f LEFT JOIN sys_user u ON u.id=f.uploader_id
            WHERE f.ticket_id=#{ticketId}
            <if test="publicOnly">AND f.visibility='PUBLIC'</if>
            ORDER BY f.id
            </script>
            """)
    List<TicketAttachment> attachments(@Param("ticketId") Long id,@Param("publicOnly") boolean publicOnly);

    @Select("SELECT * FROM ticket_attachment WHERE ticket_id=#{ticketId} AND id=#{id}")
    Optional<TicketAttachment> attachment(@Param("ticketId") Long ticketId,@Param("id") Long id);

    @Select("SELECT id FROM ticket_attachment WHERE ticket_id=#{ticketId} FOR UPDATE")
    List<Long> attachmentIdsForUpdate(@Param("ticketId") Long id);

    @Select("SELECT id FROM ticket WHERE id=#{id} FOR UPDATE")
    Long lockTicket(@Param("id") Long id);

    @Select("SELECT skills FROM ticket_support_profile WHERE user_id=#{userId}")
    String skills(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO ticket_support_profile(user_id,skills) VALUES(#{userId},#{skills})
            ON DUPLICATE KEY UPDATE skills=VALUES(skills)
            """)
    int saveSkills(@Param("userId") Long userId,@Param("skills") String skills);

    @Select("SELECT COUNT(*) FROM ticket WHERE assignee_id=#{userId} AND status IN ('IN_PROGRESS','WAITING_CUSTOMER')")
    long activeTickets(@Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO ticket_sla_reminder(ticket_id,reminder_key) VALUES(#{ticketId},#{key})")
    int claimReminder(@Param("ticketId") Long ticketId,@Param("key") String key);
}
