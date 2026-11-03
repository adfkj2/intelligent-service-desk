package com.isd.auth;

import java.util.Optional;
import com.isd.auth.domain.UserRole;
import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import com.isd.auth.security.SessionRevoker;
import com.isd.auth.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class UserAccessChangeTransactionTest {
    @Mock UserMapper users;
    @Mock PasswordEncoder passwords;
    @Mock SessionRevoker revoker;
    UserService service;
    User target;

    @BeforeEach
    void setup() {
        service=new UserService(users,passwords,revoker);
        target=new User(); target.setId(2L); target.setUsername("support");
        target.setRole(UserRole.SUPPORT); target.setStatus(1); target.setVersion(0);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach
    void clear() { TransactionSynchronizationManager.clear(); }

    @Test
    void roleRevocationRunsAfterCommitAndNeverBeforeDatabaseUpdateCommits() {
        when(users.findById(2L)).thenReturn(Optional.of(target));
        when(users.updateRole(2L,"EMPLOYEE",0)).thenReturn(1);
        service.updateRole(2L,"EMPLOYEE",1L);
        verifyNoInteractions(revoker);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(revoker).revokeAccessChanged("support");
    }

    @Test
    void rollbackDoesNotRevokeAnUnchangedAccountsSessions() {
        when(users.findById(2L)).thenReturn(Optional.of(target));
        when(users.updateStatus(2L,0,0)).thenReturn(1);
        service.updateStatus(2L,0,1L);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(revoker);
    }

    @Test
    void disablingRevokesAfterCommitAndUnchangedRoleHasNoCallback() {
        when(users.findById(2L)).thenReturn(Optional.of(target));
        service.updateRole(2L,"SUPPORT",1L);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(users,never()).updateRole(anyLong(),anyString(),anyInt());
        when(users.updateStatus(2L,0,0)).thenReturn(1);
        service.updateStatus(2L,0,1L);
        verifyNoInteractions(revoker);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(revoker).revokeAccessChanged("support");
    }
}
