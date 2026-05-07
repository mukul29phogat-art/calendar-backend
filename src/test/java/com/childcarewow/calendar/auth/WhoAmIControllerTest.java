package com.childcarewow.calendar.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WhoAmIController.class)
@Import({SecurityConfig.class, JwtToUserPrincipalConverter.class})
class WhoAmIControllerTest {

  private static TestJwtSigner signer;

  @BeforeAll
  static void initSigner() {
    signer = new TestJwtSigner();
  }

  @Autowired MockMvc mvc;
  @MockBean PlatformUserDirectory directory;

  private static final UUID STUB_ID = UUID.fromString("33333333-0000-0000-0000-000000000001");

  @BeforeEach
  void stubDirectory() {
    when(directory.load(any(UUID.class)))
        .thenReturn(
            new UserPrincipal(
                STUB_ID,
                "olivia@ccw-demo.test",
                Role.ORG_ADMIN,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Set.of(UUID.fromString("22222222-2222-2222-2222-222222222221")),
                Set.of(),
                Set.of(),
                "Owner"));
  }

  @Test
  void noAuthorizationHeaderReturns401() throws Exception {
    mvc.perform(get("/api/v1/whoami")).andExpect(status().isUnauthorized());
  }

  @Test
  void bearerWithBadSignatureReturns401() throws Exception {
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
  void validSignedTokenReturnsUserPrincipal() throws Exception {
    String token = signer.sign(STUB_ID.toString());
    mvc.perform(get("/api/v1/whoami").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(STUB_ID.toString()))
        .andExpect(jsonPath("$.email").value("olivia@ccw-demo.test"))
        .andExpect(jsonPath("$.role").value("ORG_ADMIN"))
        .andExpect(jsonPath("$.designation").value("Owner"))
        .andExpect(jsonPath("$.schoolIds").isArray())
        .andExpect(jsonPath("$.classroomIds").isArray())
        .andExpect(jsonPath("$.childStudentIds").isArray());
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
