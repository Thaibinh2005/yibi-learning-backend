package com.yibi.backend.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleLoginServiceTest {

    private static final String CLIENT = "yibi-client";
    private static KeyPair keys;
    private static KeyPair otherKeys;

    @BeforeAll
    static void makeKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com").audience(CLIENT).subject("google-1")
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("email", "Learner@Gmail.com").claim("email_verified", true).claim("name", "Learner").claim("nonce", "n1");
    }

    private static String sign(JWTClaimsSet set, KeyPair pair) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), set);
        jwt.sign(new RSASSASigner(pair.getPrivate()));
        return jwt.serialize();
    }

    private static Jwt decode(String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(GoogleLoginService.validators(CLIENT));
        return decoder.decode(token);
    }

    @Test
    void aGenuineTokenGivesTheIdentity() throws Exception {
        GoogleLoginService.GoogleIdentity identity = GoogleLoginService.identityFrom(decode(sign(claims().build(), keys)), "n1");
        assertThat(identity.subject()).isEqualTo("google-1");
        assertThat(identity.email()).isEqualTo("learner@gmail.com");
    }

    @Test
    void aTokenSignedBySomeoneElseIsRefused() throws Exception {
        String forged = sign(claims().build(), otherKeys);
        assertThatThrownBy(() -> decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenForAnotherApplicationIsRefused() throws Exception {
        String token = sign(claims().audience("someone-else").build(), keys);
        assertThatThrownBy(() -> decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() throws Exception {
        String token = sign(claims().issuer("https://evil.example").build(), keys);
        assertThatThrownBy(() -> decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        String token = sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(3600))).build(), keys);
        assertThatThrownBy(() -> decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenFromAnotherSignInIsRefused() throws Exception {
        Jwt token = decode(sign(claims().build(), keys));
        assertThatThrownBy(() -> GoogleLoginService.identityFrom(token, "other-nonce")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnverifiedEmailIsRefused() throws Exception {
        Jwt token = decode(sign(claims().claim("email_verified", false).build(), keys));
        assertThatThrownBy(() -> GoogleLoginService.identityFrom(token, "n1")).isInstanceOf(GoogleLoginService.UnverifiedEmailException.class);
    }

    @Test
    void afterSignInTheBrowserStaysOnThisSite() {
        assertThat(GoogleLoginService.safeReturnTo("/flashcards?x=1")).isEqualTo("/flashcards?x=1");
        for (String crafted : new String[] {"https://evil.example", "//evil.example", "/\\evil.example", "", null}) {
            assertThat(GoogleLoginService.safeReturnTo(crafted)).as(String.valueOf(crafted)).isEqualTo("/dashboard");
        }
    }

    @Test
    void usernamesKeepToWhatRegistrationAllows() {
        assertThat(GoogleLoginService.usernameBase("nguyen.van-a+x@gmail.com")).isEqualTo("nguyenvanax");
        assertThat(GoogleLoginService.usernameBase("a@b.c")).isEqualTo("usera");
        assertThat(GoogleLoginService.usernameBase("x".repeat(70) + "@gmail.com")).hasSize(50);
    }
}
