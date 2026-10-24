package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sayan.wallet.auth.AuthDtos.AuthResponse;
import com.sayan.wallet.auth.AuthDtos.LoginRequest;
import com.sayan.wallet.auth.AuthDtos.RegisterRequest;
import com.sayan.wallet.auth.AuthService;
import com.sayan.wallet.common.exception.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityHttpTest {
    private static final String PASSWORD = "Passw0rd!23";

    @Value("${local.server.port}")
    int port;

    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private int status(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String uniqueEmail() {
        return "sec-" + UUID.randomUUID() + "@test.com";
    }

    @Test
    void noToken_isUnauthorized() throws Exception {
        assertThat(status("GET", "/api/v1/users/me", null)).isEqualTo(401);
    }

    @Test
    void validToken_canReadProfile() throws Exception {
        AuthResponse tokens = auth.register(new RegisterRequest(uniqueEmail(), PASSWORD, "Sec User"));

        assertThat(status("GET", "/api/v1/users/me", tokens.accessToken())).isEqualTo(200);
    }

    @Test
    void userRole_isForbiddenOnAdminEndpoints() throws Exception {
        AuthResponse tokens = auth.register(new RegisterRequest(uniqueEmail(), PASSWORD, "Sec User"));

        assertThat(status("POST", "/api/v1/admin/reconciliation/runs", tokens.accessToken()))
                .isEqualTo(403);
    }

    @Test
    void adminRole_canRunReconciliation() throws Exception {
        String email = uniqueEmail();
        auth.register(new RegisterRequest(email, PASSWORD, "Admin User"));
        jdbc.update("update users set role = 'ADMIN' where email = ?", email);
        // the role lives inside the JWT, so log in again to get a token that carries it
        AuthResponse adminTokens = auth.login(new LoginRequest(email, PASSWORD));

        assertThat(status("POST", "/api/v1/admin/reconciliation/runs", adminTokens.accessToken()))
                .isEqualTo(200);
    }

    @Test
    void refreshToken_worksOnlyOnce() {
        AuthResponse tokens = auth.register(new RegisterRequest(uniqueEmail(), PASSWORD, "Sec User"));

        AuthResponse rotated = auth.refresh(tokens.refreshToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(tokens.refreshToken());

        assertThatThrownBy(() -> auth.refresh(tokens.refreshToken()))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid refresh token");
    }
}