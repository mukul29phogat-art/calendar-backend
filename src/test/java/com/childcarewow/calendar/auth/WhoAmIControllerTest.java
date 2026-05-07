package com.childcarewow.calendar.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WhoAmIController.class)
@Import(SecurityConfig.class)
class WhoAmIControllerTest {

  private static TestJwtSigner signer;

  @BeforeAll
  static void initSigner() {
    signer = new TestJwtSigner();
  }

  @Autowired MockMvc mvc;

  @Test
  void noAuthorizationHeaderReturns401() throws Exception {
    mvc.perform(get("/api/v1/whoami")).andExpect(status().isUnauthorized());
  }

  @Test
  void bearerWithBadSignatureReturns401() throws Exception {
    // Valid JWT structure but signature is junk → fails verification
    String tamperedToken =
        "eyJhbGciOiJSUzI1NiJ9."
            + "eyJzdWIiOiJ1c2VyLTEyMyIsImlzcyI6Imh0dHBzOi8vdGVzdC5zdXBhYmFzZS5sb2NhbC9hdXRoL3YxIn0."
            + "AAAAdefinitely-not-a-valid-signature";
    mvc.perform(get("/api/v1/whoami").header("Authorization", "Bearer " + tamperedToken))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")));
  }

  @Test
  void validSignedTokenReturns200WithSubject() throws Exception {
    String token = signer.sign("user-uuid-123");
    mvc.perform(get("/api/v1/whoami").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value("user-uuid-123"));
  }

  @Test
  void preflightFromAllowedOriginGets200WithCorsHeaders() throws Exception {
    mvc.perform(
            options("/api/v1/whoami")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("GET")))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  void preflightFromDisallowedOriginIsBlocked() throws Exception {
    mvc.perform(
            options("/api/v1/whoami")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }
}
