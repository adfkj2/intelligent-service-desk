package com.isd.kb.mapper;

import java.util.List;
import java.util.Optional;

import com.isd.kb.entity.KbCategory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KbCategoryMapper {

    @Insert("INSERT INTO kb_category (name, description) VALUES (#{name}, #{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KbCategory category);

    @Select("SELECT id, name, description, created_at FROM kb_category ORDER BY id")
    List<KbCategory> findAll();

    @Select("SELECT id, name, description, created_at FROM kb_category WHERE id = #{id}")
    Optional<KbCategory> findById(@Param("id") Long id);

    @Select("SELECT id, name, description, created_at FROM kb_category WHERE name = #{name}")
    Optional<KbCategory> findByName(@Param("name") String name);
}
