package com.isd.ticket.mapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.isd.ticket.entity.Ticket;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TicketMapper {
    String VIEW = "t.*, COALESCE(NULLIF(c.full_name,''),c.username,CONCAT('用户 ',t.creator_id)) creator_name,"
            + " COALESCE(NULLIF(a.full_name,''),a.username,CONCAT('用户 ',t.assignee_id)) assignee_name";
    String JOINS = " FROM ticket t LEFT JOIN sys_user c ON c.id=t.creator_id LEFT JOIN sys_user a ON a.id=t.assignee_id ";
    String FILTERS = """
            <where>
             <if test="filter.creatorId != null">AND t.creator_id=#{filter.creatorId}</if>
             <if test="filter.status != null">AND t.status=#{filter.status}</if>
             <if test="filter.category != null">AND t.category=#{filter.category}</if>
             <if test="filter.keyword != null">AND (t.title LIKE #{filter.keyword} OR t.description LIKE #{filter.keyword} OR t.ticket_no LIKE #{filter.keyword})</if>
             <if test="filter.priority != null">AND t.priority=#{filter.priority}</if>
             <if test="filter.assigneeId != null">AND t.assignee_id=#{filter.assigneeId}</if>
             <if test="filter.createdFrom != null">AND t.created_at &gt;= #{filter.createdFrom}</if>
             <if test="filter.createdTo != null">AND t.created_at &lt;= #{filter.createdTo}</if>
             <choose>
              <when test="filter.queue == 'MINE'">AND t.assignee_id=#{filter.viewerId} AND t.status IN ('IN_PROGRESS','WAITING_CUSTOMER')</when>
              <when test="filter.queue == 'UNASSIGNED'">AND t.status='OPEN' AND t.assignee_id IS NULL</when>
              <when test="filter.queue == 'WAITING_ME'">AND t.status='WAITING_CUSTOMER' AND t.creator_id=#{filter.viewerId}</when>
              <when test="filter.queue == 'CONFIRM'">AND t.status='RESOLVED' AND t.creator_id=#{filter.viewerId}</when>
              <when test="filter.queue == 'OVERDUE'">AND t.status IN ('OPEN','IN_PROGRESS') AND ((t.first_response_at IS NULL AND t.response_due_at &lt; NOW()) OR t.resolution_due_at &lt; NOW())</when>
              <when test="filter.queue == 'DUE_SOON'">AND t.status IN ('OPEN','IN_PROGRESS') AND ((t.first_response_at IS NULL AND t.response_due_at BETWEEN NOW() AND DATE_ADD(NOW(),INTERVAL 1 HOUR)) OR t.resolution_due_at BETWEEN NOW() AND DATE_ADD(NOW(),INTERVAL 1 HOUR))</when>
             </choose>
            </where>
            """;

    @Insert("""
            INSERT INTO ticket(ticket_no,title,description,category,priority,status,creator_id,idempotency_key,
                response_due_at,resolution_due_at,fill_duration_seconds)
            VALUES(#{ticketNo},#{title},#{description},#{category},#{priority},#{status},#{creatorId},#{idempotencyKey},
                #{responseDueAt},#{resolutionDueAt},#{fillDurationSeconds})
            """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(Ticket ticket);

    @Select("SELECT " + VIEW + JOINS + " WHERE t.id=#{id}")
    Optional<Ticket> findById(@Param("id") Long id);

    @Select("SELECT * FROM ticket WHERE id=#{id} FOR UPDATE")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);

    @Select("SELECT " + VIEW + JOINS + " WHERE t.creator_id=#{creatorId} AND t.idempotency_key=#{idempotencyKey}")
    Optional<Ticket> findByCreatorAndIdempotencyKey(@Param("creatorId") Long creatorId,@Param("idempotencyKey") String key);

    @Select("SELECT t.* FROM ticket t WHERE t.creator_id=#{creatorId} AND t.idempotency_key=#{idempotencyKey} FOR UPDATE")
    Optional<Ticket> findByCreatorAndIdempotencyKeyForUpdate(@Param("creatorId") Long creatorId,@Param("idempotencyKey") String key);

    @Select("SELECT COUNT(*) FROM ticket WHERE status=#{status}")
    long countByStatus(@Param("status") String status);

    @Select("<script>SELECT " + VIEW + JOINS + FILTERS + """
            ORDER BY CASE t.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                t.created_at ASC,t.id ASC LIMIT #{offset},#{size}</script>
            """)
    List<Ticket> searchAdvanced(@Param("filter") Map<String,Object> filter,@Param("offset") int offset,@Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM ticket t " + FILTERS + "</script>")
    long countAdvanced(@Param("filter") Map<String,Object> filter);

    @Update("""
            UPDATE ticket SET status=#{toStatus},assignee_id=#{assigneeId},version=version+1,updated_at=NOW()
            WHERE id=#{id} AND status=#{fromStatus} AND version=#{version}
            """)
    int updateStatusWithVersion(@Param("id") Long id,@Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,@Param("assigneeId") Long assigneeId,@Param("version") Integer version);

    @Update("""
            UPDATE ticket SET status=#{ticket.status},assignee_id=#{ticket.assigneeId},
                first_response_at=COALESCE(first_response_at,#{ticket.firstResponseAt}),resolved_at=#{ticket.resolvedAt},
                response_due_at=#{ticket.responseDueAt},resolution_due_at=#{ticket.resolutionDueAt},
                waiting_since=#{ticket.waitingSince},paused_seconds=#{ticket.pausedSeconds},
                reopen_count=#{ticket.reopenCount},version=version+1,updated_at=NOW()
            WHERE id=#{ticket.id} AND version=#{version} AND status=#{fromStatus}
            """)
    int transition(@Param("ticket") Ticket ticket,@Param("fromStatus") String fromStatus,@Param("version") Integer version);

    @Update("UPDATE ticket SET first_response_at=COALESCE(first_response_at,NOW()) WHERE id=#{id}")
    int recordFirstResponse(@Param("id") Long id);

    @Select("""
            <script>
            SELECT AVG(CASE WHEN first_response_at IS NOT NULL THEN
                GREATEST(0,TIMESTAMPDIFF(SECOND,created_at,first_response_at)) END) averageResponseSeconds,
                AVG(CASE WHEN resolved_at IS NOT NULL THEN
                GREATEST(0,TIMESTAMPDIFF(SECOND,created_at,resolved_at)-paused_seconds) END) averageResolutionSeconds,
                AVG(fill_duration_seconds) averageFillDurationSeconds,
                COALESCE(SUM(CASE WHEN reopen_count &gt; 0 THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),0) reopenRate,
                COALESCE(SUM((SELECT COUNT(*) FROM ticket_operation_log l
                    WHERE l.ticket_id=ticket.id AND l.action='REQUEST_INFO')),0) informationRequests
            FROM ticket
            <where><if test="creatorId != null">creator_id=#{creatorId}</if></where>
            </script>
            """)
    Map<String,Object> overviewMetrics(@Param("creatorId") Long creatorId);

    @Select("""
            SELECT t.* FROM ticket t WHERE t.status IN ('OPEN','IN_PROGRESS')
            AND ((t.first_response_at IS NULL AND t.response_due_at <= DATE_ADD(NOW(),INTERVAL 1 HOUR)
                AND NOT EXISTS (SELECT 1 FROM ticket_sla_reminder r WHERE r.ticket_id=t.id
                    AND r.reminder_key=CONCAT('RESPONSE:',IF(t.response_due_at < NOW(),'OVERDUE:','SOON:'),
                        DATE_FORMAT(t.response_due_at,'%Y-%m-%d %H:%i:%s'))))
                OR (t.resolution_due_at <= DATE_ADD(NOW(),INTERVAL 1 HOUR)
                AND NOT EXISTS (SELECT 1 FROM ticket_sla_reminder r WHERE r.ticket_id=t.id
                    AND r.reminder_key=CONCAT('RESOLUTION:',IF(t.resolution_due_at < NOW(),'OVERDUE:','SOON:'),
                        DATE_FORMAT(t.resolution_due_at,'%Y-%m-%d %H:%i:%s')))))
            ORDER BY t.id LIMIT 200
            """)
    List<Ticket> findSlaCandidates();
}
