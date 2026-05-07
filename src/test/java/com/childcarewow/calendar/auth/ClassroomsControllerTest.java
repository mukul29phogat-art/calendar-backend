package com.childcarewow.calendar.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

@WebMvcTest(ClassroomsController.class)
@Import({SecurityConfig.class, JwtToUserPrincipalConverter.class})
class ClassroomsControllerTest {

  private static final UUID OLIVIA_ID = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static TestJwtSigner signer;

  @BeforeAll
  static void initSigner() {
    signer = new TestJwtSigner();
  }

  @Autowired MockMvc mvc;
  @MockBean PlatformUserDirectory directory;
  @MockBean ClassroomsReadService classroomsReadService;
  @MockBean com.childcarewow.calendar.crosscut.IdempotencyKeyRepository idempotencyKeyRepository;

  @Test
  void authenticatedReturnsClassroomsWithStaff() throws Exception {
    when(directory.load(any(UUID.class)))
        .thenReturn(
            new UserPrincipal(
                OLIVIA_ID,
                "Olivia",
                "olivia@ccw.test",
                Role.ORG_ADMIN,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Set.of(SUNRISE),
                Set.of(),
                Set.of(),
                "Owner"));
    when(classroomsReadService.findBySchool(eq(SUNRISE)))
        .thenReturn(List.of(new ClassroomView(BUTTERFLIES, SUNRISE, "Butterflies", List.of(MAYA))));

    String token = signer.sign(OLIVIA_ID.toString());
    mvc.perform(
            get("/api/v1/classrooms")
                .param("schoolId", SUNRISE.toString())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(BUTTERFLIES.toString()))
        .andExpect(jsonPath("$[0].schoolId").value(SUNRISE.toString()))
        .andExpect(jsonPath("$[0].name").value("Butterflies"))
        .andExpect(jsonPath("$[0].staffUserIds[0]").value(MAYA.toString()));
  }

  @Test
  void unauthenticatedGets401() throws Exception {
    mvc.perform(get("/api/v1/classrooms").param("schoolId", SUNRISE.toString()))
        .andExpect(status().isUnauthorized());
  }
}
