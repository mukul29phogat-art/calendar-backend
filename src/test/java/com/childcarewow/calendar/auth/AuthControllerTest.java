package com.childcarewow.calendar.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtToUserPrincipalConverter.class})
class AuthControllerTest {

  private static TestJwtSigner signer;

  @BeforeAll
  static void initSigner() {
    signer = new TestJwtSigner();
  }

  @Autowired MockMvc mvc;
  @MockBean PlatformUserDirectory directory;

  private static final UUID PRIYA_PARENT = UUID.fromString("33333333-0000-0000-0000-000000000006");

  @BeforeEach
  void stubParent() {
    when(directory.load(any(UUID.class)))
        .thenReturn(
            new UserPrincipal(
                PRIYA_PARENT,
                "Priya Singh",
                "priya@parent.test",
                Role.PARENT,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Set.of(UUID.fromString("22222222-2222-2222-2222-222222222221")),
                Set.of(),
                Set.of(UUID.fromString("55555555-0000-0000-0000-000000000001")),
                null));
  }

  @Test
  void parentMeReturnsExpectedShape() throws Exception {
    String token = signer.sign(PRIYA_PARENT.toString());
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        // Field names match the prototype's User type exactly.
        .andExpect(jsonPath("$.id").value(PRIYA_PARENT.toString()))
        .andExpect(jsonPath("$.name").value("Priya Singh"))
        .andExpect(jsonPath("$.email").value("priya@parent.test"))
        .andExpect(jsonPath("$.role").value("PARENT"))
        .andExpect(jsonPath("$.schoolIds").isArray())
        .andExpect(jsonPath("$.childStudentIds").isArray())
        // designation is null for PARENT in the seed; @JsonInclude(NON_EMPTY) elides it.
        .andExpect(jsonPath("$.designation").doesNotExist())
        // classroomIds empty for parents — also elided.
        .andExpect(jsonPath("$.classroomIds").doesNotExist());
  }

  @Test
  void unauthenticatedMeReturns401() throws Exception {
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
  }
}
