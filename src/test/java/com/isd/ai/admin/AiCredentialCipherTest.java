package com.isd.ai.admin;

import com.isd.common.exception.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiCredentialCipherTest {
    @TempDir Path directory;
    @Test void localMasterPersistsAndEncryptionAuthenticatesIdentityAndUsesFreshNonce() throws Exception {
        AiConfigurationMapper mapper=mock(AiConfigurationMapper.class);
        Path file=directory.resolve("secrets/master.key");
        AiCredentialCipher first=new AiCredentialCipher(mapper,"",file.toString(),true,new MockEnvironment());
        assertThat(first.ready()).isTrue();assertThat(Files.readString(file)).hasSize(44);
        String a=first.encrypt("sk-private-model-key","1:1"),b=first.encrypt("sk-private-model-key","1:1");
        assertThat(a).isNotEqualTo(b).doesNotContain("sk-private-model-key");
        assertThat(first.decrypt(a,"1:1")).isEqualTo("sk-private-model-key");
        AiCredentialCipher restarted=new AiCredentialCipher(mapper,"",file.toString(),true,new MockEnvironment());
        assertThat(restarted.decrypt(a,"1:1")).isEqualTo("sk-private-model-key");
        assertThatThrownBy(()->restarted.decrypt(a,"1:2")).isInstanceOf(BusinessException.class).hasMessageContaining("解密失败");
        assertThatThrownBy(()->restarted.decrypt("v1:AAAA","1:1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->restarted.decrypt("unknown","1:1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->restarted.decrypt(null,"1:1")).isInstanceOf(BusinessException.class);
    }
    @Test void existingCiphertextsPreventRegenerationOfLostMaster() {
        AiConfigurationMapper mapper=mock(AiConfigurationMapper.class);when(mapper.encryptedCount()).thenReturn(1L);
        Path file=directory.resolve("missing.key");
        AiCredentialCipher cipher=new AiCredentialCipher(mapper,"",file.toString(),true,new MockEnvironment());
        assertThat(cipher.ready()).isFalse();
        assertThatThrownBy(()->cipher.encrypt("secret","1:1")).isInstanceOf(BusinessException.class).hasMessageContaining("主密钥丢失");
        assertThat(file).doesNotExist();
    }
    @Test void productionRequiresExplicitMasterAndBadKeysFailSafely() throws Exception {
        AiConfigurationMapper mapper=mock(AiConfigurationMapper.class);
        MockEnvironment prod=new MockEnvironment();prod.setActiveProfiles("prod");
        Path file=directory.resolve("prod.key");
        AiCredentialCipher missing=new AiCredentialCipher(mapper,"",file.toString(),true,prod);
        assertThat(missing.ready()).isFalse();assertThat(file).doesNotExist();
        assertThatThrownBy(()->missing.encrypt("secret","1:1")).hasMessageContaining("ISD_AI_MASTER_KEY");
        AiCredentialCipher configured=new AiCredentialCipher(mapper,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",file.toString(),false,prod);
        assertThat(configured.ready()).isTrue();
        String value=configured.encrypt("sk-long-private-key","1:1");
        assertThat(configured.decrypt(value,"1:1")).isEqualTo("sk-long-private-key");
        AiCredentialCipher wrong=new AiCredentialCipher(mapper,"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",file.toString(),false,prod);
        assertThatThrownBy(()->wrong.decrypt(value,"1:1")).hasMessageContaining("解密失败");
        assertThat(new AiCredentialCipher(mapper,"bad",file.toString(),true,prod).ready()).isFalse();
        assertThat(new AiCredentialCipher(mapper,"AAAA",file.toString(),true,prod).ready()).isFalse();
        Files.writeString(file,"invalid-file-key");
        assertThat(new AiCredentialCipher(mapper,"",file.toString(),false,prod).ready()).isFalse();
    }
    @Test void changedMasterCannotCreateAMixedEncryptionEpoch() {
        AiConfigurationMapper mapper=mock(AiConfigurationMapper.class);
        AiCredentialCipher original=new AiCredentialCipher(mapper,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                directory.resolve("master").toString(),false,new MockEnvironment());
        AiConfigurationRow existing=new AiConfigurationRow();existing.id=1L;existing.version=1L;
        existing.keyCiphertext=original.encrypt("sk-existing-private-key","isd-ai:1:1");
        when(mapper.firstEncrypted()).thenReturn(existing);
        assertThat(original.ready()).isTrue();
        AiCredentialCipher changed=new AiCredentialCipher(mapper,"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                directory.resolve("master").toString(),false,new MockEnvironment());
        assertThat(changed.ready()).isFalse();
        assertThatThrownBy(()->changed.encrypt("sk-new-private-key","isd-ai:2:1")).hasMessageContaining("不匹配");
        existing.keyCiphertext="broken";
        assertThat(original.ready()).isFalse();
    }
}
