package com.childcarewow.calendar.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * Signs RS256 JWTs with the test-only RSA private key in src/test/resources. Used by the auth slice
 * tests; never wired into the running app.
 */
public final class TestJwtSigner {

  private final PrivateKey privateKey;

  public TestJwtSigner() {
    this.privateKey = loadPrivateKey();
  }

  public String sign(String subject) {
    return sign(subject, Instant.now().plusSeconds(60));
  }

  public String sign(String subject, Instant expiry) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .issuer("https://test.supabase.local/auth/v1")
              .issueTime(java.util.Date.from(Instant.now()))
              .expirationTime(java.util.Date.from(expiry))
              .build();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .type(com.nimbusds.jose.JOSEObjectType.JWT)
                  .build(),
              claims);
      jwt.sign(new RSASSASigner(privateKey));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("test JWT signing failed", e);
    }
  }

  private static PrivateKey loadPrivateKey() {
    try {
      String pem =
          new String(
              StreamUtils.copyToByteArray(
                  new ClassPathResource("supabase-jwt-private-key.pem").getInputStream()));
      String body =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace("-----BEGIN RSA PRIVATE KEY-----", "")
              .replace("-----END RSA PRIVATE KEY-----", "")
              .replaceAll("\\s+", "");
      byte[] der = Base64.getDecoder().decode(body);
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (IOException | InvalidKeySpecException | java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(
          "could not load test private key from classpath:supabase-jwt-private-key.pem", e);
    }
  }
}
