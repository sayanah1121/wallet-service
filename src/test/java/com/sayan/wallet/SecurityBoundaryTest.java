package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

// Category 5.1: JWT tampering, expiry and cross-account access on POST /api/v1/transfers.
class SecurityBoundaryTest extends HttpTestBase {

    @Value("${app.jwt.secret}")
    String secret;

    private SecretKey realKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] parts(String jwt) {
        return jwt.split("\\.");
    }

    // A victim with money and an attacker trying to move it; returns the status of the attempt.
    private int attemptTransfer(String token, UUID victim, UUID beneficiary) throws Exception {
        int status = send(transferRequest(token, "sec-" + UUID.randomUUID(), emailOf(beneficiary), 1_000)).status();
        assertThat(balanceOf(victim)).as("victim balance must be untouched").isEqualTo(10_000);
        return status;
    }

    @Test
    @DisplayName("TC-5.1a: expired token -> 401")
    void expiredToken_isUnauthorized() throws Exception {
        UUID victim = newUser(10_000);
        UUID other = newUser(0);
        Instant past = Instant.now().minusSeconds(3_600);
        String expired = Jwts.builder()
                .subject(victim.toString())
                .claim("role", "USER")
                .issuedAt(Date.from(past.minusSeconds(900)))
                .expiration(Date.from(past))
                .signWith(realKey())
                .compact();

        assertThat(attemptTransfer(expired, victim, other)).isEqualTo(401);
    }

    @Test
    @DisplayName("TC-5.1b: signature-tampered token -> 401")
    void tamperedSignature_isUnauthorized() throws Exception {
        UUID victim = newUser(10_000);
        UUID other = newUser(0);
        String[] p = parts(tokenOf(victim));
        char last = p[2].charAt(5);
        String tampered = p[0] + "." + p[1] + "." + p[2].substring(0, 5) + (last == 'A' ? 'B' : 'A') + p[2].substring(6);

        assertThat(attemptTransfer(tampered, victim, other)).isEqualTo(401);
    }

    @Test
    @DisplayName("TC-5.1c: payload swapped to another user's id (original signature kept) -> 401")
    void payloadSwappedToVictim_isUnauthorized() throws Exception {
        UUID attacker = newUser(0);
        UUID victim = newUser(10_000);
        String[] p = parts(tokenOf(attacker));
        long exp = Instant.now().plusSeconds(600).getEpochSecond();
        String forgedPayload = b64("{\"sub\":\"%s\",\"role\":\"USER\",\"exp\":%d}".formatted(victim, exp));

        assertThat(attemptTransfer(p[0] + "." + forgedPayload + "." + p[2], victim, attacker)).isEqualTo(401);
    }

    @Test
    @DisplayName("TC-5.1d: token signed with a different secret -> 401")
    void foreignKey_isUnauthorized() throws Exception {
        UUID victim = newUser(10_000);
        UUID attacker = newUser(0);
        String forged = Jwts.builder()
                .subject(victim.toString())
                .claim("role", "USER")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(("attacker-controlled-secret-" + UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(attemptTransfer(forged, victim, attacker)).isEqualTo(401);
    }

    @Test
    @DisplayName("TC-5.1e: unsigned 'alg: none' token -> 401")
    void algNone_isUnauthorized() throws Exception {
        UUID victim = newUser(10_000);
        UUID attacker = newUser(0);
        long exp = Instant.now().plusSeconds(600).getEpochSecond();
        String unsigned = b64("{\"alg\":\"none\"}") + "."
                + b64("{\"sub\":\"%s\",\"role\":\"USER\",\"exp\":%d}".formatted(victim, exp)) + ".";

        assertThat(attemptTransfer(unsigned, victim, attacker)).isEqualTo(401);
    }

    @Test
    @DisplayName("TC-5.1f: garbage token, wrong scheme or no token -> 401")
    void malformedOrMissing_isUnauthorized() throws Exception {
        UUID victim = newUser(10_000);
        UUID attacker = newUser(0);
        String toAttacker = emailOf(attacker);

        assertThat(attemptTransfer("not.a.jwt", victim, attacker)).isEqualTo(401);
        assertThat(send(transferRequest(null, "k-" + UUID.randomUUID(), toAttacker, 1_000)).status()).isEqualTo(401);
        assertThat(send(transferRequest(null, "k-" + UUID.randomUUID(), toAttacker, 1_000)
                .header("Authorization", "Basic " + tokenOf(victim))).status()).isEqualTo(401);
        assertThat(balanceOf(victim)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("TC-5.1g: role claim escalated to ADMIN (signature kept) -> 401 on admin API")
    void roleEscalation_isUnauthorized() throws Exception {
        UUID user = newUser(0);
        String[] p = parts(tokenOf(user));
        long exp = Instant.now().plusSeconds(600).getEpochSecond();
        String escalated = p[0] + "." + b64("{\"sub\":\"%s\",\"role\":\"ADMIN\",\"exp\":%d}".formatted(user, exp))
                + "." + p[2];

        assertThat(send(jsonPost("/api/v1/admin/reconciliation/runs", escalated, "")).status()).isEqualTo(401);
        assertThat(send(jsonPost("/api/v1/admin/reconciliation/runs", tokenOf(user), "")).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("TC-5.1h: user A cannot debit user B's wallet by naming it in the request")
    void cannotDebitAnotherUsersWallet() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(10_000);
        UUID c = newUser(0);
        String tokenA = tokenOf(a);

        // the sender always comes from the JWT subject; spoofed "from" fields are ignored
        String body = """
                {"toEmail":"%s","amount":1000,"fromUserId":"%s","fromWalletId":"%s","senderId":"%s"}
                """.formatted(emailOf(c), b, walletIdOf(b), b);
        Reply reply = send(jsonPost("/api/v1/transfers", tokenA, body)
                .header("Idempotency-Key", "spoof-" + UUID.randomUUID()));

        assertThat(reply.status()).isEqualTo(201);
        assertThat(balanceOf(b)).as("B is never debited").isEqualTo(10_000);
        assertThat(balanceOf(a)).isEqualTo(9_000);
        assertThat(balanceOf(c)).isEqualTo(1_000);

        // and B's token can only ever see B's wallet
        Reply wallet = send(get("/api/v1/wallets/me", tokenOf(b)));
        assertThat(wallet.json().get("id").asString()).isEqualTo(walletIdOf(b).toString());
    }
}
