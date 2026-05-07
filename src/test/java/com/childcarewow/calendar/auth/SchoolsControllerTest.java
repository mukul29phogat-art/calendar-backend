package com.childcarewow.calendar.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link SchoolsController}. Verifies the actor-passthrough into {@link
 * SchoolsReadService} and the response shape — the role-aware visibility logic itself is
 * unit-tested in {@link SchoolsReadServiceIT}.
 */
@WebMvcTest(SchoolsController.class)
@Import({SecurityConfig.class, JwtToUserPrincipalConverter.class})
class SchoolsControllerTest {

  private static final UUID OLIVIA_ID = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static TestJwtSigner signer;

  @BeforeAll
  static void initSigner() {
    signer = new TestJwtSigner();
  }

  @Autowired MockMvc mvc;
  @MockBean PlatformUserDirectory directory;
  @MockBean SchoolsReadService schoolsReadService;

  @MockBean com.childcarewow.calendar.crosscut.IdempotencyKeyRepository idempotencyKeyRepository;

  @Test
  void authenticatedReturnsList() throws Exception {
    when(directory.load(any(UUID.class)))
        .thenReturn(
            new UserPrincipal(
                OLIVIA_ID,
                "Olivia Park",
                "olivia@ccw.test",
                Role.ORG_ADMIN,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Set.of(SUNRISE, MAPLEWOOD),
                Set.of(),
                Set.of(),
                "Owner"));
    when(schoolsReadService.findVisibleTo(any()))
        .thenReturn(
            List.of(
                new SchoolView(SUNRISE, "Sunrise Preschool", "America/New_York"),
                new SchoolView(MAPLEWOOD, "Maplewood Preschool", "America/Chicago")));

    String token = signer.sign(OLIVIA_ID.toString());
    mvc.perform(get("/api/v1/schools").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(SUNRISE.toString()))
        .andExpect(jsonPath("$[0].name").value("Sunrise Preschool"))
        .andExpect(jsonPath("$[0].timezone").value("America/New_York"))
        .andExpect(jsonPath("$[1].id").value(MAPLEWOOD.toString()))
        .andExpect(jsonPath("$[1].timezone").value("America/Chicago"));
  }

  @Test
  void unauthenticatedGets401() throws Exception {
    mvc.perform(get("/api/v1/schools")).andExpect(status().isUnauthorized());
  }
}
