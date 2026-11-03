package com.isd.ai.admin;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AiConfigurationMapper {
    @Select("SELECT COUNT(*) FROM ai_provider_version WHERE key_ciphertext<>''") long encryptedCount();
    @Select("SELECT configuration_id AS id,version,key_ciphertext FROM ai_provider_version WHERE key_ciphertext<>'' ORDER BY configuration_id,version LIMIT 1")
    AiConfigurationRow firstEncrypted();
    @Insert("INSERT INTO ai_provider_configuration(created_by) VALUES(#{createdBy})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertConfiguration(AiConfigurationRow row);
    @Insert("""
        INSERT INTO ai_provider_version(configuration_id,version,name,provider,base_url,model,
          key_ciphertext,key_suffix,currency,input_price,cached_input_price,output_price,created_by)
        VALUES(#{id},#{version},#{name},#{provider},#{baseUrl},#{model},#{keyCiphertext},#{keySuffix},
          #{currency},#{inputPrice},#{cachedInputPrice},#{outputPrice},#{createdBy})
        """) int insertVersion(AiConfigurationRow row);
    @Select("""
        SELECT c.id,c.latest_version,c.archived,v.* FROM ai_provider_configuration c
        JOIN ai_provider_version v ON v.configuration_id=c.id AND v.version=c.latest_version
        ORDER BY c.archived,c.id DESC
        """) List<AiConfigurationRow> list();
    @Select("""
        SELECT c.id,c.latest_version,c.archived,v.* FROM ai_provider_configuration c
        JOIN ai_provider_version v ON v.configuration_id=c.id AND v.version=c.latest_version WHERE c.id=#{id}
        """) AiConfigurationRow latest(long id);
    @Select("SELECT id,latest_version,archived FROM ai_provider_configuration WHERE id=#{id} FOR UPDATE")
    AiConfigurationRow lockConfiguration(long id);
    @Select("""
        SELECT c.id,c.latest_version,c.archived,v.* FROM ai_provider_configuration c
        JOIN ai_provider_version v ON v.configuration_id=c.id WHERE c.id=#{id} AND v.version=#{version}
        """) AiConfigurationRow version(@Param("id")long id,@Param("version")long version);
    @Select("""
        SELECT c.id,c.latest_version,c.archived,v.* FROM ai_provider_configuration c
        JOIN ai_provider_version v ON v.configuration_id=c.id WHERE c.id=#{id} ORDER BY v.version DESC
        """) List<AiConfigurationRow> versions(long id);
    @Update("UPDATE ai_provider_configuration SET latest_version=#{version} WHERE id=#{id}")
    int advance(@Param("id")long id,@Param("version")long version);
    @Update("""
        UPDATE ai_provider_version v JOIN ai_provider_configuration c ON c.id=v.configuration_id
        SET v.test_status=#{status},v.test_message=#{message},v.tested_at=NOW()
        WHERE v.configuration_id=#{id} AND v.version=#{version} AND c.latest_version=#{version} AND c.archived=0
        """) int tested(@Param("id")long id,@Param("version")long version,@Param("status")String status,
                          @Param("message")String message);
    @Update("UPDATE ai_provider_configuration SET archived=1 WHERE id=#{id}") int archive(long id);
    @Update("UPDATE ai_provider_version SET key_ciphertext='' WHERE configuration_id=#{id}") int eraseArchivedSecrets(long id);
    @Select("SELECT mode,version,configuration_id,configuration_version FROM ai_runtime_setting WHERE id=1")
    AiRuntimeRow runtime();
    @Select("SELECT mode,version,configuration_id,configuration_version FROM ai_runtime_setting WHERE id=1 FOR UPDATE")
    AiRuntimeRow lockRuntime();
    @Update("""
        UPDATE ai_runtime_setting SET mode=#{mode},configuration_id=#{configurationId},
        configuration_version=#{configurationVersion},version=version+1,updated_by=#{actor},updated_at=NOW() WHERE id=1
        """) int runtimeUpdate(@Param("mode")String mode,@Param("configurationId")Long id,
                                 @Param("configurationVersion")Long version,@Param("actor")Long actor);
    @Insert("""
        INSERT INTO ai_configuration_audit(actor_id,action,configuration_id,configuration_version)
        VALUES(#{actor},#{action},#{id},#{version})
        """) int audit(@Param("actor")long actor,@Param("action")String action,@Param("id")Long id,@Param("version")Long version);
    @Select("SELECT * FROM ai_provider_balance WHERE configuration_id=#{id} AND configuration_version=#{version}")
    AiBalanceRow balance(@Param("id")long id,@Param("version")long version);
    @Insert("""
        INSERT INTO ai_provider_balance(configuration_id,configuration_version,balance_json,available,checked_at,attempted_at)
        VALUES(#{id},#{version},#{json},#{available},NOW(),NOW())
        ON DUPLICATE KEY UPDATE balance_json=VALUES(balance_json),available=VALUES(available),checked_at=NOW(),attempted_at=NOW(),last_error=NULL
        """) int balanceSuccess(@Param("id")long id,@Param("version")long version,@Param("json")String json,@Param("available")boolean available);
    @Insert("""
        INSERT INTO ai_provider_balance(configuration_id,configuration_version,attempted_at,last_error)
        VALUES(#{id},#{version},NOW(),#{message})
        ON DUPLICATE KEY UPDATE attempted_at=NOW(),last_error=VALUES(last_error)
        """) int balanceFailure(@Param("id")long id,@Param("version")long version,@Param("message")String message);
}
