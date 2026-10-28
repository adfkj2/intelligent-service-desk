package com.isd.ticket.mapper;

import java.util.List;

import com.isd.ticket.entity.TicketOperationLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TicketOperationLogMapper {

    /** comment 是 MySQL 关键字，列名用反引号包住 */
    @Insert("""
            INSERT INTO ticket_operation_log (ticket_id, operator_id, action, from_status, to_status, `comment`)
            VALUES (#{ticketId}, #{operatorId}, #{action}, #{fromStatus}, #{toStatus}, #{comment})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TicketOperationLog log);

    @Select("""
            SELECT id, ticket_id, operator_id, action, from_status, to_status, `comment`, created_at
            FROM ticket_operation_log
            WHERE ticket_id = #{ticketId}
            ORDER BY id
            """)
    List<TicketOperationLog> findByTicketId(@Param("ticketId") Long ticketId);
}
