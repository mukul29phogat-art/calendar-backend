package com.childcarewow.calendar.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.childcarewow.calendar.auth.JwtToUserPrincipalConverter;
import com.childcarewow.calendar.auth.PlatformUserDirectory;
import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.SecurityConfig;
import com.childcarewow.calendar.auth.TestJwtSigner;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.policy.PolicyServiceImpl;
import java.time.OffsetDateTime;
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

@WebMvcTest(EventController.class)
@Import({SecurityConfig.class, JwtToUserPrincipalConverter.class, PolicyServiceImpl.class})
class EventControllerTest {

  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static TestJwtSigner signer;

  @BeforeAll
  static void initSigner() {
    signer = new TestJwtSigner();
  }

  @Autowired MockMvc mvc;
  @MockBean PlatformUserDirectory directory;
  @MockBean EventService service;
  @MockBean com.childcarewow.calendar.crosscut.IdempotencyKeyRepository idempotencyKeyRepository;

  @Test
  void adminCanCreateEvent() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(OLIVIA, Role.ORG_ADMIN));
    when(service.create(any(), any()))
        .thenReturn(
            new EventView(
                UUID.randomUUID(),
                EventType.CLASSROOM,
                SUNRISE,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                BUTTERFLIES,
                "Storytime",
                null,
                OffsetDateTime.parse("2026-09-15T14:00:00-04:00"),
                OffsetDateTime.parse("2026-09-15T14:30:00-04:00"),
                false,
                OLIVIA,
                false,
                OLIVIA,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

    String token = signer.sign(OLIVIA.toString());
    mvc.perform(
            post("/api/v1/events")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(validClassroomBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("Storytime"))
        .andExpect(jsonPath("$.type").value("CLASSROOM"));
  }

  @Test
  void parentGets403() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(PRIYA, Role.PARENT));

    String token = signer.sign(PRIYA.toString());
    mvc.perform(
            post("/api/v1/events")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(validClassroomBody()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    verify(service, never()).create(any(), any());
  }

  @Test
  void unauthenticatedGets401() throws Exception {
    mvc.perform(
            post("/api/v1/events").contentType("application/json").content(validClassroomBody()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void invalidBodyReturns400Envelope() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(OLIVIA, Role.ORG_ADMIN));
    String token = signer.sign(OLIVIA.toString());
    // Missing required fields (no startDt/endDt/title).
    mvc.perform(
            post("/api/v1/events")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"type\":\"CLASSROOM\",\"schoolId\":\"" + SUNRISE + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  // -- helpers ----------------------------------------------------------------

  private static UserPrincipal actor(UUID id, Role role) {
    return new UserPrincipal(
        id,
        "Test",
        "test@ccw.test",
        role,
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES),
        Set.of(),
        role == Role.ORG_ADMIN ? "Owner" : null);
  }

  private static String validClassroomBody() {
    return "{"
        + "\"type\":\"CLASSROOM\","
        + "\"schoolId\":\""
        + SUNRISE
        + "\","
        + "\"title\":\"Storytime\","
        + "\"classroomId\":\""
        + BUTTERFLIES
        + "\","
        + "\"startDt\":\"2026-09-15T14:00:00-04:00\","
        + "\"endDt\":\"2026-09-15T14:30:00-04:00\""
        + "}";
  }
}
