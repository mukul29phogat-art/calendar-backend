package com.childcarewow.calendar.calendar;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.childcarewow.calendar.auth.JwtToUserPrincipalConverter;
import com.childcarewow.calendar.auth.PlatformUserDirectory;
import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.SecurityConfig;
import com.childcarewow.calendar.auth.TestJwtSigner;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.event.EventType;
import com.childcarewow.calendar.event.EventView;
import com.childcarewow.calendar.exception.ValidationException;
import com.childcarewow.calendar.policy.PolicyServiceImpl;
import java.time.LocalDate;
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

@WebMvcTest(CalendarController.class)
@Import({SecurityConfig.class, JwtToUserPrincipalConverter.class, PolicyServiceImpl.class})
class CalendarControllerTest {

  private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static TestJwtSigner signer;

  @BeforeAll
  static void initSigner() {
    signer = new TestJwtSigner();
  }

  @Autowired MockMvc mvc;
  @MockBean PlatformUserDirectory directory;
  @MockBean CalendarReadService service;
  @MockBean com.childcarewow.calendar.crosscut.IdempotencyKeyRepository idempotencyKeyRepository;

  @Test
  void happyPathReturnsKindEventLowercaseAndDateAndData() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(OLIVIA, Role.ORG_ADMIN));
    when(service.read(any(), any(), any(), any(), any())).thenReturn(List.of(sampleEventItem()));

    String token = signer.sign(OLIVIA.toString());
    mvc.perform(
            get("/api/v1/calendar")
                .header("Authorization", "Bearer " + token)
                .queryParam("schoolId", SUNRISE.toString())
                .queryParam("from", "2026-05-01")
                .queryParam("to", "2026-05-31"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].kind").value("event"))
        .andExpect(jsonPath("$[0].date").value("2026-05-15"))
        .andExpect(jsonPath("$[0].data.title").value("Storytime"))
        .andExpect(jsonPath("$[0].data.type").value("CLASSROOM"));
  }

  @Test
  void emptyWindowReturnsEmptyArray() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(OLIVIA, Role.ORG_ADMIN));
    when(service.read(any(), any(), any(), any(), any())).thenReturn(List.of());

    String token = signer.sign(OLIVIA.toString());
    mvc.perform(
            get("/api/v1/calendar")
                .header("Authorization", "Bearer " + token)
                .queryParam("schoolId", SUNRISE.toString())
                .queryParam("from", "2026-05-01")
                .queryParam("to", "2026-05-01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void unauthenticatedReturns401() throws Exception {
    mvc.perform(
            get("/api/v1/calendar")
                .queryParam("schoolId", SUNRISE.toString())
                .queryParam("from", "2026-05-01")
                .queryParam("to", "2026-05-31"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rangeBeyond366DaysReturns400ValidationEnvelope() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(OLIVIA, Role.ORG_ADMIN));
    // The real service is the source of truth on the 366-day cap, but the slice test mocks it.
    // Have the mock throw the same exception the real service raises — this verifies the envelope
    // mapping in GlobalExceptionHandler still emits the right error code and field for this path.
    when(service.read(any(), any(), any(), any(), any()))
        .thenThrow(new ValidationException("to", "window may not exceed 366 days"));

    String token = signer.sign(OLIVIA.toString());
    mvc.perform(
            get("/api/v1/calendar")
                .header("Authorization", "Bearer " + token)
                .queryParam("schoolId", SUNRISE.toString())
                .queryParam("from", "2026-01-01")
                .queryParam("to", "2028-01-01"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.error.field").value("to"));
  }

  // Note: malformed date params (e.g. `from=not-a-date`) currently fall through to the generic
  // 500 path because GlobalExceptionHandler has no mapper for MethodArgumentTypeMismatchException
  // / MissingServletRequestParameterException. Same gap was flagged in Part 4.1 progress.md and is
  // still open as a Series-wide validation polish (handler + test bundle).

  // -- helpers ---------------------------------------------------------------

  private static UserPrincipal actor(UUID id, Role role) {
    return new UserPrincipal(
        id,
        "Test",
        "test@ccw.test",
        role,
        ORG,
        Set.of(SUNRISE),
        Set.of(BUTTERFLIES),
        Set.of(),
        role == Role.ORG_ADMIN ? "Owner" : null);
  }

  private static EventCalendarItem sampleEventItem() {
    EventView ev =
        new EventView(
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            EventType.CLASSROOM,
            SUNRISE,
            ORG,
            BUTTERFLIES,
            "Storytime",
            null,
            OffsetDateTime.parse("2026-05-15T14:00:00-04:00"),
            OffsetDateTime.parse("2026-05-15T14:30:00-04:00"),
            false,
            OLIVIA,
            false,
            OLIVIA,
            OffsetDateTime.parse("2026-04-01T10:00:00Z"),
            OffsetDateTime.parse("2026-04-01T10:00:00Z"),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    return new EventCalendarItem(LocalDate.of(2026, 5, 15), ev);
  }
}
