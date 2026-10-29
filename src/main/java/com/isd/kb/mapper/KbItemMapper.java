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
 * 两条检索路径分工明确：
 * <ul>
 *   <li>{@link #search} / {@link #countSearch}：面向"列表页关键词搜索"，仍是 LIKE '%kw%'。
 *       前置通配符用不上 B+ 树索引，数据量上千就是全表扫描；但列表页 QPS 低，且要支持
 *       "s" 这种单字符查询，LIKE 是唯一能保证语义的写法。</li>
 *   <li>{@link #searchFulltext}：面向 RAG 召回（V3 加了 ngram 全文索引）。
 *       相关性排序由 MATCH ... AGAINST 给出，专治"整句当关键词"命中率低的问题。</li>
 * </ul>
 * 为什么两者不合并：全文检索对单字符、纯符号是查不出来的（ngram 最小切 2 字），
 * 直接替换会让现有搜索退化，所以是增量而不是替换。
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

    /**
     * RAG 召回：全文检索 + 相关性排序，返回带正文的完整条目（拼提示词要用）。
     *
     * 用 NATURAL LANGUAGE MODE 而不是 BOOLEAN MODE：前者对自然语言问句更友好，
     * BOOLEAN 模式里的 + - * " 等符号对用户输入来说全是噪音，还得先清洗。
     */
    @Select("""
            SELECT i.id, i.category_id, i.title, i.content, i.tags, i.created_by, i.created_at, i.updated_at,
                   c.name AS category_name
            FROM kb_item i
            LEFT JOIN kb_category c ON c.id = i.category_id
            WHERE MATCH(i.title, i.content, i.tags) AGAINST (#{query} IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(i.title, i.content, i.tags) AGAINST (#{query} IN NATURAL LANGUAGE MODE) DESC, i.id DESC
            LIMIT #{limit}
            """)
    List<KbItem> searchFulltext(@Param("query") String query, @Param("limit") int limit);

    /**
     * 全文召回为空时的兜底（单字、纯符号、ngram 切不出词的情况）。
     * 只服务 RAG，所以要带正文；列表页那条 search 故意不带 content，避免白白传输大字段。
     */
    @Select("""
            SELECT i.id, i.category_id, i.title, i.content, i.tags, i.created_by, i.created_at, i.updated_at,
                   c.name AS category_name
            FROM kb_item i
            LEFT JOIN kb_category c ON c.id = i.category_id
            WHERE (i.title LIKE #{keyword} OR i.content LIKE #{keyword} OR i.tags LIKE #{keyword})
            ORDER BY i.id DESC
            LIMIT #{limit}
            """)
    List<KbItem> searchLikeWithContent(@Param("keyword") String keyword, @Param("limit") int limit);

    @Update("""
            UPDATE kb_item
            SET category_id = #{categoryId}, title = #{title}, content = #{content}, tags = #{tags}
            WHERE id = #{id}
            """)
    int update(KbItem item);

    @Delete("DELETE FROM kb_item WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
