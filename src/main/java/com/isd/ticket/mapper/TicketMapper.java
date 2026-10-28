package com.isd.ticket.mapper;

import java.util.List;
import java.util.Optional;

import com.isd.ticket.entity.Ticket;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 工单数据访问接口。
 * 没有实现类：MyBatis 在启动时为它生成动态代理，把方法上的 SQL 与参数绑定后执行。
 *
 * 复杂动态 SQL（分页 + 多条件）仍写在注解里，因为条件只有 4 个；
 * 一旦超过 2~3 个可选条件、或需要复用 <sql> 片段，就该迁到 resources/mapper/*.xml。
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

    @Select("SELECT COUNT(*) FROM ticket WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    /**
     * 分页 + 条件查询。
     * creatorId 为 null 表示"看全部"（SUPPORT / ADMIN），
     * 这个参数由 Service 依据当前登录角色计算，绝不接受前端传入。
     */
    @Select("""
            <script>
            SELECT id, ticket_no, title, description, category, priority, status,
                   creator_id, assignee_id, version, created_at, updated_at
            FROM ticket
            <where>
                <if test="creatorId != null">AND creator_id = #{creatorId}</if>
                <if test="status != null">AND status = #{status}</if>
                <if test="category != null">AND category = #{category}</if>
                <if test="keyword != null">AND (title LIKE #{keyword} OR description LIKE #{keyword})</if>
            </where>
            ORDER BY id DESC
            LIMIT #{offset}, #{size}
            </script>
            """)
    List<Ticket> search(@Param("creatorId") Long creatorId,
                        @Param("status") String status,
                        @Param("category") String category,
                        @Param("keyword") String keyword,
                        @Param("offset") int offset,
                        @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ticket
            <where>
                <if test="creatorId != null">AND creator_id = #{creatorId}</if>
                <if test="status != null">AND status = #{status}</if>
                <if test="category != null">AND category = #{category}</if>
                <if test="keyword != null">AND (title LIKE #{keyword} OR description LIKE #{keyword})</if>
            </where>
            </script>
            """)
    long countSearch(@Param("creatorId") Long creatorId,
                     @Param("status") String status,
                     @Param("category") String category,
                     @Param("keyword") String keyword);

    /**
     * 乐观锁更新：把「状态必须还是 fromStatus」和「version 必须没被别人改过」都写进 WHERE。
     * 返回 0 行 = 有人抢先，业务层转 409；返回 1 行 = 本次迁移生效。
     * 注意不要写成 SELECT ... FOR UPDATE（悲观锁）：接单是短事务且冲突可重试，乐观锁更划算。
     */
    @Update("""
            UPDATE ticket
            SET status = #{toStatus},
                assignee_id = #{assigneeId},
                version = version + 1,
                updated_at = NOW()
            WHERE id = #{id} AND status = #{fromStatus} AND version = #{version}
            """)
    int updateStatusWithVersion(@Param("id") Long id,
                                @Param("fromStatus") String fromStatus,
                                @Param("toStatus") String toStatus,
                                @Param("assigneeId") Long assigneeId,
                                @Param("version") Integer version);
}
