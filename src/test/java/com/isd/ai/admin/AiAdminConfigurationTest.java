package com.isd.ai.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.auth.security.LoginUser;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"isd.ai.credentials.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "isd.ai.allowed-origins=https://models.example.com","spring.ai.openai.api-key=sk-env-test-admin"})
@AutoConfigureMockMvc
@Transactional
class AiAdminConfigurationTest extends AbstractIntegrationTest {
    private static final LoginUser ADMIN=TestUsers.admin(99701L);
    private static final String SECRET="sk-test-admin-never-return-9831";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiConfigurationService configurations;
    @MockitoBean AiConnectionTester tester;

    @Test void masksKeysEncryptsAtRestAndRequiresTestBeforeActivation() throws Exception {
        JsonNode created=create();long id=created.path("id").asLong();
        assertThat(created.toString()).doesNotContain(SECRET,"keyCiphertext","apiKey");
        assertThat(created.path("keySuffix").asText()).isEqualTo("9831");
        String stored=jdbc.queryForObject("SELECT key_ciphertext FROM ai_provider_version WHERE configuration_id=?",String.class,id);
        assertThat(stored).startsWith("v1:").doesNotContain(SECRET);
        runtime(id,1,0).andExpect(status().isConflict());
        test(id,1).andExpect(status().isOk()).andExpect(jsonPath("$.data.testStatus").value("PASSED"));
        runtime(id,1,0).andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("MANAGED"));
        assertThat(configurations.resolve().orElseThrow().apiKey()).isEqualTo(SECRET);
        assertThat(configurations.resolve().orElseThrow().toString()).doesNotContain(SECRET);
        verify(tester).test(any());
        mvc.perform(get("/api/admin/ai/configurations").with(user(ADMIN)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }

    @Test void editedDraftDoesNotReplaceCurrentAndHistoricalVersionCanBeReactivated() throws Exception {
        long id=create().path("id").asLong();test(id,1);runtime(id,1,0);
        Map<String,Object> edit=body();edit.put("version",1);edit.put("apiKey","");edit.put("model","deepseek-other");
        JsonNode saved=data(mvc.perform(put("/api/admin/ai/configurations/{id}",id).with(user(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit))));
        assertThat(saved.path("version").asLong()).isEqualTo(2);
        assertThat(saved.path("testStatus").asText()).isEqualTo("UNTESTED");
        assertThat(configurations.resolve().orElseThrow().configurationVersion()).isEqualTo(1);
        assertThat(configurations.resolve().orElseThrow().model()).isEqualTo("deepseek-chat");
        test(id,1).andExpect(status().isConflict());
        test(id,2).andExpect(status().isOk());runtime(id,2,1).andExpect(status().isOk());
        assertThat(configurations.resolve().orElseThrow().model()).isEqualTo("deepseek-other");
        runtime(id,1,2).andExpect(status().isOk());
        mvc.perform(get("/api/admin/ai/configurations/{id}/versions",id).with(user(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(put("/api/admin/ai/configurations/{id}",id).with(user(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
                .andExpect(status().isConflict());
    }

    @Test void replacementKeyIsStagedDisableNeverReturnsToEnvironmentAndArchiveErasesSecrets() throws Exception {
        assertThat(configurations.runtime().mode()).isEqualTo("ENV");
        assertThat(configurations.resolve().orElseThrow().apiKey()).isEqualTo("sk-env-test-admin");
        long id=create().path("id").asLong();test(id,1);runtime(id,1,0);
        Map<String,Object> edit=body();edit.put("version",1);edit.put("apiKey","sk-new-key-7821");
        data(mvc.perform(put("/api/admin/ai/configurations/{id}",id).with(user(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit))));
        assertThat(configurations.resolve().orElseThrow().apiKey()).isEqualTo(SECRET);
        mvc.perform(post("/api/admin/ai/configurations/{id}/archive",id).with(user(ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/admin/ai/runtime").with(user(ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"mode\":\"DISABLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.keyConfigured").value(false));
        assertThat(configurations.resolve()).isEmpty();
        runtime(id,1,1).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/ai/configurations/{id}/archive",id).with(user(ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT key_ciphertext FROM ai_provider_version WHERE configuration_id=?",String.class,id)).containsOnly("");
        runtime(id,1,2).andExpect(status().isConflict());
        test(id,2).andExpect(status().isConflict());
    }

    @Test void sanitizesProviderFailuresAndRefusesInvalidConfigurations() throws Exception {
        long id=create().path("id").asLong();
        doThrow(new IllegalStateException("echoed-secret "+SECRET)).when(tester).test(any());
        String failed=test(id,1).andExpect(status().isOk()).andExpect(jsonPath("$.data.testStatus").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(failed).doesNotContain(SECRET,"echoed-secret");
        runtime(id,1,0).andExpect(status().isConflict());
        Map<String,Object> invalid=body();invalid.put("baseUrl","https://unapproved.example.com/v1");
        save(invalid).andExpect(status().isBadRequest());
        invalid=body();invalid.put("apiKey","bad");save(invalid).andExpect(status().isBadRequest());
        invalid=body();invalid.put("inputPrice","1.25");save(invalid).andExpect(status().isBadRequest());
        invalid=body();invalid.put("inputPrice","-1");invalid.put("currency","CNY");save(invalid).andExpect(status().isBadRequest());
        invalid=body();invalid.put("name","");save(invalid).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/ai/configurations/99999999/versions").with(user(ADMIN))).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/ai/configurations/99999999/balance").with(user(ADMIN))).andExpect(status().isNotFound());
    }

    @Test void supportsCustomServicesAndReportsBalanceCapabilityWithoutFinancialCredentials() throws Exception {
        Map<String,Object> custom=body();custom.put("provider","CUSTOM");custom.put("baseUrl","https://models.example.com/v1/");
        custom.put("currency","USD");custom.put("inputPrice","1.20");custom.put("cachedInputPrice","0");custom.put("outputPrice","3.40");
        JsonNode result=data(save(custom));long id=result.path("id").asLong();
        assertThat(result.path("baseUrl").asText()).isEqualTo("https://models.example.com/v1");
        mvc.perform(get("/api/admin/ai/configurations/{id}/balance",id).with(user(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.supported").value(false));
        mvc.perform(post("/api/admin/ai/configurations/{id}/balance/refresh",id).with(user(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balances.length()").value(0));
        long deepseek=create().path("id").asLong();
        mvc.perform(get("/api/admin/ai/configurations/{id}/balance",deepseek).with(user(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.supported").value(true)).andExpect(jsonPath("$.data.stale").value(true));
    }

    @Test void deniesAllConfigurationActionsToNonAdministrators() throws Exception {
        for(LoginUser actor:new LoginUser[]{TestUsers.employee(99702L),TestUsers.support(99703L)}) {
            mvc.perform(get("/api/admin/ai/configurations").with(user(actor))).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/ai/runtime").with(user(actor))).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/ai/configurations").with(user(actor)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body())))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/admin/ai/runtime").with(user(actor)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"mode\":\"DISABLED\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/ai/configurations/1/test").with(user(actor)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/ai/configurations/1/balance/refresh").with(user(actor))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/ai/configurations")).andExpect(status().isUnauthorized());
    }

    private Map<String,Object> body() {
        Map<String,Object> map=new LinkedHashMap<>();map.put("name","DeepSeek 测试配置");map.put("provider","DEEPSEEK");
        map.put("baseUrl","https://api.deepseek.com");map.put("model","deepseek-chat");map.put("apiKey",SECRET);return map;
    }
    private ResultActions save(Map<String,Object> body) throws Exception {
        return mvc.perform(post("/api/admin/ai/configurations").with(user(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    private JsonNode create() throws Exception { return data(save(body())); }
    private ResultActions test(long id,long version) throws Exception {
        return mvc.perform(post("/api/admin/ai/configurations/{id}/test",id).with(user(ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}"));
    }
    private ResultActions runtime(long id,long version,long runtimeVersion) throws Exception {
        return mvc.perform(put("/api/admin/ai/runtime").with(user(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(
                "{\"mode\":\"MANAGED\",\"configurationId\":"+id+",\"configurationVersion\":"+version+",\"version\":"+runtimeVersion+"}"));
    }
    private JsonNode data(ResultActions action) throws Exception {
        return json.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
}
