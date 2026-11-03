package com.isd.ai.admin;

import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class AiCredentialCipher {
    private final AiConfigurationMapper mapper;
    private final String configuredMaster;
    private final Path keyFile;
    private final boolean allowGeneration;
    private final SecureRandom random = new SecureRandom();

    public AiCredentialCipher(AiConfigurationMapper mapper,
            @Value("${isd.ai.credentials.master-key:${ISD_AI_MASTER_KEY:}}") String master,
            @Value("${isd.ai.credentials.key-file:${ISD_AI_MASTER_KEY_FILE:.local/ai-master.key}}") String file,
            @Value("${isd.ai.credentials.allow-local-generation:true}") boolean generate,
            Environment environment) {
        this.mapper=mapper;
        this.configuredMaster=master;
        this.keyFile=Path.of(file).toAbsolutePath().normalize();
        this.allowGeneration=generate && !environment.acceptsProfiles(Profiles.of("prod"));
    }

    public boolean ready() {
        try { master(); return true; } catch (BusinessException ex) { return false; }
    }

    public String encrypt(String secret, String identity) {
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master(),"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] envelope=new byte[nonce.length+encrypted.length];
            System.arraycopy(nonce,0,envelope,0,nonce.length);
            System.arraycopy(encrypted,0,envelope,nonce.length,encrypted.length);
            return "v1:"+Base64.getEncoder().encodeToString(envelope);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw unavailable("无法加密 API Key，请检查部署主密钥配置"); }
    }

    public String decrypt(String encrypted, String identity) {
        try {
            if (encrypted==null || !encrypted.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] envelope=Base64.getDecoder().decode(encrypted.substring(3));
            if (envelope.length<29) throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master(),"AES"),
                    new GCMParameterSpec(128,Arrays.copyOf(envelope,12)));
            cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Arrays.copyOfRange(envelope,12,envelope.length)),StandardCharsets.UTF_8);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw unavailable("API Key 解密失败，请恢复原部署主密钥，或重新录入 Key"); }
    }

    private synchronized byte[] master() {
        try {
            String encoded=configuredMaster==null?"":configuredMaster.trim();
            if (encoded.isEmpty()) {
                if (Files.isSymbolicLink(keyFile)) throw unavailable("主密钥文件不能使用符号链接");
                if (!Files.exists(keyFile)) {
                    if (mapper.encryptedCount()>0) throw unavailable("已有加密 Key，但主密钥丢失，请恢复原主密钥");
                    if (!allowGeneration) throw unavailable("请部署 ISD_AI_MASTER_KEY 或主密钥文件后再管理 API Key");
                    Files.createDirectories(keyFile.getParent());
                    byte[] generated=new byte[32]; random.nextBytes(generated);
                    String value=Base64.getEncoder().encodeToString(generated);
                    try {
                        try {
                            Files.createFile(keyFile,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                        } catch (UnsupportedOperationException ex) { Files.createFile(keyFile); }
                        Files.writeString(keyFile,value,StandardCharsets.US_ASCII,StandardOpenOption.WRITE);
                    } catch (FileAlreadyExistsException ex) {
                        // A concurrent local bootstrap already created the shared file; never replace it.
                    }
                }
                encoded=Files.readString(keyFile,StandardCharsets.US_ASCII).trim();
            }
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if (bytes.length!=32) throw unavailable("部署主密钥必须为 Base64 编码的 32 字节随机值");
            verifyExistingMaster(bytes);
            return bytes;
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw unavailable("无法读取部署主密钥，请检查文件及访问权限"); }
    }

    private void verifyExistingMaster(byte[] master) {
        AiConfigurationRow existing=mapper.firstEncrypted();
        if(existing==null) return;
        try {
            if(existing.keyCiphertext==null || !existing.keyCiphertext.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] envelope=Base64.getDecoder().decode(existing.keyCiphertext.substring(3));
            if(envelope.length<29) throw new IllegalArgumentException();
            Cipher proof=Cipher.getInstance("AES/GCM/NoPadding");
            proof.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,Arrays.copyOf(envelope,12)));
            proof.updateAAD(("isd-ai:"+existing.id+":"+existing.version).getBytes(StandardCharsets.UTF_8));
            byte[] clear=proof.doFinal(Arrays.copyOfRange(envelope,12,envelope.length));
            Arrays.fill(clear,(byte)0);
        } catch(Exception ex) {
            throw unavailable("部署主密钥与已有加密 Key 不匹配，请恢复原主密钥后再管理 Key");
        }
    }

    private BusinessException unavailable(String message) { return new BusinessException(ErrorCode.CONFLICT,message); }
}
