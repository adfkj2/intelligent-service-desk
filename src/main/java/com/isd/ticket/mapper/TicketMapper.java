package com.isd.ticket.mapper;

import java.util.Optional;

import com.isd.ticket.entity.Ticket;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工单数据访问接口。
 * 没有实现类：MyBatis 在启动时为它生成动态代理，把方法上的 SQL 与参数绑定后执行。
 */
@Mapper
public interface TicketMapper {

    @Insert("""
            INSERT INTO ticket (ticket_no, title, description, category, priority, status, creator_id)
            VALUES (#{ticketNo}, #{title}, #{description}, #{category}, #{priority}, #{status}, #{creatorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Ticket ticket);

    @Select("""
            SELECT id, ticket_no, title, description, category, priority, status,
                   creator_id, assignee_id, version, created_at, updated_at
            FROM ticket
            WHERE id = #{id}
            """)
    Optional<Ticket> findById(@Param("id") Long id);
}
