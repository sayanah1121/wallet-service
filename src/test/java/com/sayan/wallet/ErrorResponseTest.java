package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sayan.wallet.transaction.TransferDtos.TransferResponse;
import com.sayan.wallet.transaction.TransferService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// Part 2, category 7: what the client sees when the transfer path fails with an unexpected or
// a transient database error. TransferService is mocked to inject the failures.
class ErrorResponseTest extends HttpTestBase {

    @MockitoBean TransferService transferService;

    private Reply post(String token) throws Exception {
        return send(transferRequest(token, "err-" + UUID.randomUUID(), "someone@test.com", 100));
    }

    @Test
    @DisplayName("P2 TC-2.1c: an unhandled server error reaches an authenticated client as 500, not 401")
    void unhandledError_is500() throws Exception {
        when(transferService.transfer(any(), anyString(), any())).thenThrow(new IllegalStateException("boom"));

        Reply reply = post(tokenOf(newUser(0)));

        TestMetrics.record("P2-TC-2.1c", "status_for_unhandled_error", reply.status());
        assertThat(reply.status()).isEqualTo(500);
    }

    @Test
    @DisplayName("P2 TC-2.1d: a transient lock conflict is retried with backoff and the transfer succeeds")
    void transientConflict_isRetried() throws Exception {
        TransferResponse ok = new TransferResponse(UUID.randomUUID(), 900, "INR");
        when(transferService.transfer(any(), anyString(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock detected"))
                .thenThrow(new CannotAcquireLockException("deadlock detected"))
                .thenReturn(ok);

        Reply reply = post(tokenOf(newUser(0)));

        assertThat(reply.status()).isEqualTo(201);
        verify(transferService, times(3)).transfer(any(), anyString(), any());
        TestMetrics.record("P2-TC-2.1d", "attempts_until_success", 3);
    }

    @Test
    @DisplayName("P2 TC-2.1e: a conflict that outlasts the retries returns 503 with Retry-After")
    void persistentConflict_is503() throws Exception {
        when(transferService.transfer(any(), anyString(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock detected"));

        Reply reply = post(tokenOf(newUser(0)));

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.headers().firstValue("Retry-After")).contains("1");
        assertThat(reply.json().get("status").asInt()).isEqualTo(503);
        verify(transferService, times(4)).transfer(any(), anyString(), any());
        TestMetrics.record("P2-TC-2.1e", "attempts", 4);
        TestMetrics.record("P2-TC-2.1e", "status", reply.status());
    }
}
