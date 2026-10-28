package com.isd.kb.mapper;

import java.util.List;
import java.util.Optional;

import com.isd.kb.entity.KbItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 知识条目数据访问。
 *
 * 检索用 LIKE '%kw%'：前置通配符使 B+ 树索引完全用不上（idx_title 只能用于 'kw%' 形式），
 * 数据量上千后就是全表扫描。这是 v1 明确接受的代价：
 * 说清楚"为什么这条 SQL 用不上索引"以及 v2 用 FULLTEXT ngram / ES 的方案，比硬塞一个 Redis 更有价值。
 */
@Mapper
public interface KbItemMapper {

    @Insert("""
            INSERT INTO kb_item (category_id, title, content, tags, created_by)
            VALUES (#{categoryId}, #{title}, #{content}, #{tags}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KbItem item);

    @Select("""
            SELECT i.id, i.category_id, i.title, i.content, i.tags, i.created_by, i.created_at, i.updated_at,
                   c.name AS category_name
            FROM kb_item i
            LEFT JOIN kb_category c ON c.id = i.category_id
            WHERE i.id = #{id}
            """)
    Optional<KbItem> findById(@Param("id") Long id);

    /**
     * 列表与检索共用一条 SQL：categoryId、keyword 都可为空。
     * 用一条动态 SQL 而不是两条静态 SQL，避免"加了筛选条件却忘了给 count 也加"这类不一致。
     */
    @Select("""
            <script>
            SELECT i.id, i.category_id, i.title, i.tags, i.created_by, i.updated_at,
                   c.name AS category_name
            FROM kb_item i
            LEFT JOIN kb_category c ON c.id = i.category_id
            <where>
                <if test="categoryId != null">AND i.category_id = #{categoryId}</if>
                <if test="keyword != null">
                    AND (i.title LIKE #{keyword} OR i.content LIKE #{keyword} OR i.tags LIKE #{keyword})
                </if>
            </where>
            ORDER BY i.id DESC
            LIMIT #{offset}, #{size}
            </script>
            """)
    List<KbItem> search(@Param("categoryId") Long categoryId,
                        @Param("keyword") String keyword,
                        @Param("offset") int offset,
                        @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM kb_item i
            <where>
                <if test="categoryId != null">AND i.category_id = #{categoryId}</if>
                <if test="keyword != null">
                    AND (i.title LIKE #{keyword} OR i.content LIKE #{keyword} OR i.tags LIKE #{keyword})
                </if>
            </where>
            </script>
            """)
    long countSearch(@Param("categoryId") Long categoryId,
                     @Param("keyword") String keyword);

    @Update("""
            UPDATE kb_item
            SET category_id = #{categoryId}, title = #{title}, content = #{content}, tags = #{tags}
            WHERE id = #{id}
            """)
    int update(KbItem item);

    @Delete("DELETE FROM kb_item WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
