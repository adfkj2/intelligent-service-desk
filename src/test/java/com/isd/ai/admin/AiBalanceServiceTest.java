package com.isd.ai.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.ai.admin.AiAdminDtos.*;
import com.isd.common.time.BusinessTime;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AiBalanceServiceTest {
    AiConfigurationMapper mapper;
    AiConfigurationService configurations;
    MockRestServiceServer server;
    AiBalanceService service;
    AiConfigurationRow config;
    AtomicReference<AiBalanceRow> cache;
    @BeforeEach void setup() {
        mapper=mock(AiConfigurationMapper.class);configurations=mock(AiConfigurationService.class);
        RestClient.Builder builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        service=new AiBalanceService(mapper,configurations,new ObjectMapper().findAndRegisterModules(),builder.build());
        config=new AiConfigurationRow();config.id=11L;config.version=2L;config.provider="DEEPSEEK";config.archived=false;
        when(mapper.latest(11)).thenReturn(config);
        when(configurations.configurationSnapshot(11,2)).thenReturn(new AiRuntimeSnapshot(11L,2L,"DEEPSEEK","test",
                "https://api.deepseek.com","deepseek-chat","sk-synthetic-test-key","-key",null,null,null,null));
        cache=new AtomicReference<>();
        when(mapper.balance(11,2)).thenAnswer(call->cache.get());
        when(mapper.balanceSuccess(eq(11L),eq(2L),anyString(),anyBoolean())).thenAnswer(call->{
            AiBalanceRow row=new AiBalanceRow();row.balanceJson=call.getArgument(2);row.available=call.getArgument(3);
            row.checkedAt=BusinessTime.now();row.attemptedAt=row.checkedAt;cache.set(row);return 1;
        });
        when(mapper.balanceFailure(eq(11L),eq(2L),anyString())).thenAnswer(call->{
            AiBalanceRow row=cache.get();if(row==null) row=new AiBalanceRow();
            row.attemptedAt=BusinessTime.now();row.lastError=call.getArgument(2);cache.set(row);return 1;
        });
    }
    @Test void queriesOfficialAccountBalanceParsesDecimalsAndThrottlesRepeatRefresh() {
        server.expect(requestTo("https://api.deepseek.com/user/balance")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization","Bearer sk-synthetic-test-key"))
                .andRespond(withSuccess("""
                    {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.3456","granted_balance":"2","topped_up_balance":"10.3456"}]}
                    """,MediaType.APPLICATION_JSON));
        BalanceResponse result=service.refresh(11);
        assertThat(result.supported()).isTrue();assertThat(result.stale()).isFalse();assertThat(result.available()).isTrue();
        assertThat(result.balances()).containsExactly(new BalanceAmount("CNY",new BigDecimal("12.3456"),new BigDecimal("2"),new BigDecimal("10.3456")));
        assertThat(result.message()).contains("账户余额");
        assertThat(service.refresh(11).checkedAt()).isEqualTo(result.checkedAt());server.verify();
    }
    @Test void retainsPreviousBalanceOnFailureAndNeverPersistsProviderMessage() {
        AiBalanceRow previous=new AiBalanceRow();previous.balanceJson="[{\"currency\":\"USD\",\"totalBalance\":5,\"grantedBalance\":0,\"toppedUpBalance\":5}]";
        previous.available=true;previous.checkedAt=BusinessTime.now().minusHours(1);previous.attemptedAt=previous.checkedAt;cache.set(previous);
        server.expect(requestTo("https://api.deepseek.com/user/balance")).andRespond(withUnauthorizedRequest().body("echo sk-private-leak"));
        BalanceResponse result=service.refresh(11);
        assertThat(result.stale()).isTrue();assertThat(result.checkedAt()).isEqualTo(previous.checkedAt);
        assertThat(result.balances()).hasSize(1);assertThat(result.message()).contains("已保留").doesNotContain("sk-private-leak");server.verify();
    }
    @Test void indicatesMissingOrMalformedBalanceAndHandlesMalformedOfficialResponses() {
        assertThat(service.balance(11).message()).contains("尚未查询");
        AiBalanceRow row=new AiBalanceRow();row.checkedAt=BusinessTime.now();row.balanceJson="not-json";cache.set(row);
        assertThat(service.balance(11).stale()).isTrue();assertThat(service.balance(11).message()).contains("不可读");
        cache.set(null);
        server.expect(requestTo("https://api.deepseek.com/user/balance")).andRespond(withSuccess("{\"is_available\":true,\"balance_infos\":[]}",MediaType.APPLICATION_JSON));
        assertThat(service.refresh(11).balances()).isEmpty();assertThat(service.balance(11).message()).contains("失败");server.verify();
    }
    @Test void showsProviderCapabilityAndRejectsMissingOrArchivedConfigurations() {
        config.provider="OPENAI";
        assertThat(service.balance(11).supported()).isFalse();assertThat(service.balance(11).consoleUrl()).contains("openai.com");
        config.provider="QWEN";assertThat(service.refresh(11).consoleUrl()).contains("aliyun.com");
        config.provider="CUSTOM";assertThat(service.balance(11).consoleUrl()).isNull();
        config.provider="DEEPSEEK";config.archived=true;
        assertThatThrownBy(()->service.refresh(11)).hasMessageContaining("已归档");
        assertThatThrownBy(()->service.balance(404)).hasMessageContaining("不存在");
        verifyNoInteractions(configurations);
    }
}
