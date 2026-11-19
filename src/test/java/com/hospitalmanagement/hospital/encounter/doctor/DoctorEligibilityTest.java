package com.hospitalmanagement.hospital.encounter.doctor;

import com.hospitalmanagement.hospital.client.UserServiceClient;
import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DoctorEligibilityTest {
    @Test
    void capsConcurrentLookupsAndReleasesPermits() throws Exception {
        var client = mock(UserServiceClient.class);
        var eligibility = new DoctorEligibility(client);
        var account = UUID.randomUUID();
        var entered = new CountDownLatch(8);
        var release = new CountDownLatch(1);

        given(client.getAccountIdentity(account)).willAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();

            return new UserServiceClient.AccountIdentity(account, true, 4);
        });

        var executor = Executors.newFixedThreadPool(8);
        var pending = new ArrayList<Future<?>>();

        try {
            for (int lookup = 0; lookup < 8; lookup++) {
                pending.add(executor.submit(() -> eligibility.requireEligibleAccount(account)));
            }

            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> eligibility.requireEligibleAccount(account))
                    .isInstanceOfSatisfying(BusinessException.class, failure ->
                            assertThat(failure.getErrorCode())
                                    .isEqualTo(ErrorCode.DOCTOR_ACCOUNT_UNAVAILABLE));
            verify(client, times(8)).getAccountIdentity(account);

            release.countDown();

            for (var verification : pending) {
                verification.get(10, TimeUnit.SECONDS);
            }

            eligibility.requireEligibleAccount(account);
            verify(client, times(9)).getAccountIdentity(account);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
