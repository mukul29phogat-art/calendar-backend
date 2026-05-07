package com.childcarewow.calendar.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.childcarewow.calendar.audit.AuditReadAspect;
import com.childcarewow.calendar.audit.AuditService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link StudentsController}. Imports the {@link AuditReadAspect} alongside the
 * security plumbing so we can verify the COPPA audit row is written.
 */
@WebMvcTest(StudentsController.class)
@Import({
  SecurityConfig.class,
  JwtToUserPrincipalConverter.class,
  AopAutoConfiguration.class,
  AuditReadAspect.class
})
class StudentsControllerTest {

  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID AANYA = UUID.fromString("55555555-0000-0000-0000-000000000001");
  private static final UUID JORDAN = UUID.fromString("55555555-0000-0000-0000-000000000002");

  private static TestJwtSigner signer;

  @BeforeAll
  static void initSigner() {
    signer = new TestJwtSigner();
  }

  @Autowired MockMvc mvc;
  @MockBean PlatformUserDirectory directory;
  @MockBean StudentsReadService readService;
  @MockBean AuditService auditService;
  @MockBean com.childcarewow.calendar.crosscut.IdempotencyKeyRepository idempotencyKeyRepository;

  @Test
  void successWritesOneStudentViewAuditRow() throws Exception {
    when(directory.load(any(UUID.class)))
        .thenReturn(
            new UserPrincipal(
                OLIVIA,
                "Olivia",
                "olivia@ccw.test",
                Role.ORG_ADMIN,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Set.of(SUNRISE),
                Set.of(),
                Set.of(),
                "Owner"));
    when(readService.findByScope(any(), eq(BUTTERFLIES), any()))
        .thenReturn(
            List.of(
                new StudentView(
                    AANYA, SUNRISE, BUTTERFLIES, "Aanya Singh", LocalDate.of(2021, 4, 12)),
                new StudentView(
                    JORDAN, SUNRISE, BUTTERFLIES, "Jordan Becker", LocalDate.of(2020, 11, 3))));

    String token = signer.sign(OLIVIA.toString());
    mvc.perform(
            get("/api/v1/students")
                .param("classroomId", BUTTERFLIES.toString())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(AANYA.toString()))
        .andExpect(jsonPath("$[0].name").value("Aanya Singh"))
        .andExpect(jsonPath("$[0].dob").value("2021-04-12"))
        .andExpect(jsonPath("$[1].id").value(JORDAN.toString()));

    verify(auditService, times(1))
        .log(
            eq(OLIVIA),
            eq("STUDENT_VIEW"),
            eq("STUDENT"),
            eq(null),
            any(),
            any(),
            argThat(
                meta -> {
                  Object subjects = meta.get("subject_ids");
                  return subjects instanceof java.util.Collection<?> coll
                      && coll.containsAll(List.of(AANYA, JORDAN))
                      && coll.size() == 2;
                }));
  }

  @Test
  void parentReadAlsoWritesAuditRow() throws Exception {
    when(directory.load(any(UUID.class)))
        .thenReturn(
            new UserPrincipal(
                PRIYA,
                "Priya",
                "priya@parent.test",
                Role.PARENT,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Set.of(SUNRISE),
                Set.of(),
                Set.of(AANYA),
                null));
    when(readService.findByScope(any(), eq(BUTTERFLIES), any()))
        .thenReturn(
            List.of(
                new StudentView(
                    AANYA, SUNRISE, BUTTERFLIES, "Aanya Singh", LocalDate.of(2021, 4, 12))));

    String token = signer.sign(PRIYA.toString());
    mvc.perform(
            get("/api/v1/students")
                .param("classroomId", BUTTERFLIES.toString())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(AANYA.toString()));

    verify(auditService, times(1))
        .log(
            eq(PRIYA),
            eq("STUDENT_VIEW"),
            eq("STUDENT"),
            eq(null),
            any(),
            any(),
            argThat(
                meta -> {
                  Object subjects = meta.get("subject_ids");
                  return subjects instanceof java.util.Collection<?> coll
                      && coll.size() == 1
                      && coll.contains(AANYA);
                }));
  }

  @Test
  void emptyResultStillWritesAuditRow() throws Exception {
    when(directory.load(any(UUID.class)))
        .thenReturn(
            new UserPrincipal(
                OLIVIA,
                "Olivia",
                "olivia@ccw.test",
                Role.ORG_ADMIN,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Set.of(SUNRISE),
                Set.of(),
                Set.of(),
                "Owner"));
    when(readService.findByScope(any(), any(), any())).thenReturn(List.of());

    String token = signer.sign(OLIVIA.toString());
    mvc.perform(
            get("/api/v1/students")
                .param("classroomId", BUTTERFLIES.toString())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    verify(auditService, times(1))
        .log(
            eq(OLIVIA),
            eq("STUDENT_VIEW"),
            eq("STUDENT"),
            eq(null),
            any(),
            any(),
            argThat(
                meta -> {
                  Object subjects = meta.get("subject_ids");
                  return subjects instanceof java.util.Collection<?> coll && coll.isEmpty();
                }));
  }

  @Test
  void unauthenticatedGets401NoAuditRow() throws Exception {
    mvc.perform(get("/api/v1/students").param("classroomId", BUTTERFLIES.toString()))
        .andExpect(status().isUnauthorized());
    verify(auditService, never()).log(any(), any(), any(), any(), any(), any(), any());
  }
}
