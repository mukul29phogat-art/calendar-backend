package com.childcarewow.calendar.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskController.class)
@Import({SecurityConfig.class, JwtToUserPrincipalConverter.class, PolicyServiceImpl.class})
class TaskControllerTest {

  private static final UUID OLIVIA = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID PRIYA = UUID.fromString("33333333-0000-0000-0000-000000000006");
  private static final UUID MAYA = UUID.fromString("33333333-0000-0000-0000-000000000004");
  private static final UUID SUNRISE = UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID BUTTERFLIES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static TestJwtSigner signer;

  @BeforeAll
  static void initSigner() {
    signer = new TestJwtSigner();
  }

  @Autowired MockMvc mvc;
  @MockBean PlatformUserDirectory directory;
  @MockBean TaskService service;
  @MockBean TaskReadService readService;
  @MockBean com.childcarewow.calendar.crosscut.IdempotencyKeyRepository idempotencyKeyRepository;

  @Test
  void adminCanCreateTask() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(OLIVIA, Role.ORG_ADMIN));
    when(service.create(any(), any()))
        .thenReturn(
            java.util.List.of(
                new TaskView(
                    UUID.randomUUID(),
                    SUNRISE,
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    BUTTERFLIES,
                    "Storytime prep",
                    null,
                    MAYA,
                    LocalDate.of(2026, 9, 15),
                    null,
                    TaskStatus.TODO,
                    TaskPriority.MEDIUM,
                    null,
                    null,
                    OffsetDateTime.now(),
                    OffsetDateTime.now())));

    String token = signer.sign(OLIVIA.toString());
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(validBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$[0].title").value("Storytime prep"))
        .andExpect(jsonPath("$[0].status").value("TODO"))
        .andExpect(jsonPath("$[0].assigneeUserId").value(MAYA.toString()));
  }

  @Test
  void parentGets403() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(PRIYA, Role.PARENT));

    String token = signer.sign(PRIYA.toString());
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(validBody()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    verify(service, never()).create(any(), any());
  }

  @Test
  void unauthenticatedGets401() throws Exception {
    mvc.perform(post("/api/v1/tasks").contentType("application/json").content(validBody()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void invalidBodyReturns400Envelope() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(OLIVIA, Role.ORG_ADMIN));
    String token = signer.sign(OLIVIA.toString());
    // Missing required fields (no title, no dueDate, no assigneeUserIds, no schoolId).
    mvc.perform(
            post("/api/v1/tasks")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void listReturnsArrayOfTaskViews() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(OLIVIA, Role.ORG_ADMIN));
    when(readService.findInWindow(any(), any(), any(), any()))
        .thenReturn(java.util.List.of(sampleTaskView()));

    String token = signer.sign(OLIVIA.toString());
    mvc.perform(
            get("/api/v1/tasks")
                .header("Authorization", "Bearer " + token)
                .queryParam("schoolId", SUNRISE.toString())
                .queryParam("from", "2026-09-01")
                .queryParam("to", "2026-09-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("Storytime prep"))
        .andExpect(jsonPath("$[0].assigneeUserId").value(MAYA.toString()));
  }

  @Test
  void findByIdReturnsTaskView() throws Exception {
    when(directory.load(any(UUID.class))).thenReturn(actor(OLIVIA, Role.ORG_ADMIN));
    TaskView view = sampleTaskView();
    when(readService.findById(any(), any())).thenReturn(view);

    String token = signer.sign(OLIVIA.toString());
    mvc.perform(get("/api/v1/tasks/{id}", view.id()).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Storytime prep"))
        .andExpect(jsonPath("$.id").value(view.id().toString()));
  }

  @Test
  void unauthenticatedListReturns401() throws Exception {
    mvc.perform(
            get("/api/v1/tasks")
                .queryParam("schoolId", SUNRISE.toString())
                .queryParam("from", "2026-09-01")
                .queryParam("to", "2026-09-30"))
        .andExpect(status().isUnauthorized());
  }

  private static TaskView sampleTaskView() {
    return new TaskView(
        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        SUNRISE,
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        BUTTERFLIES,
        "Storytime prep",
        null,
        MAYA,
        LocalDate.of(2026, 9, 15),
        null,
        TaskStatus.TODO,
        TaskPriority.MEDIUM,
        null,
        null,
        OffsetDateTime.now(),
        OffsetDateTime.now());
  }

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

  private static String validBody() {
    return "{"
        + "\"title\":\"Storytime prep\","
        + "\"schoolId\":\""
        + SUNRISE
        + "\","
        + "\"assigneeUserIds\":[\""
        + MAYA
        + "\"],"
        + "\"dueDate\":\"2026-09-15\""
        + "}";
  }
}
