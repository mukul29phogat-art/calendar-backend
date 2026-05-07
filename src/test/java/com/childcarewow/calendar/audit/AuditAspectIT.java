package com.childcarewow.calendar.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Integration test for the {@link AuditAspect}. Rather than going through MockMvc + the security
 * filter chain, this test directly invokes a test-only Spring-managed bean whose methods carry
 * {@link Audited}. The aspect intercepts the call exactly as it does in production.
 *
 * <p>{@link AuditService} is replaced with a mock; we verify that the aspect calls it with the
 * right arguments (and not at all when the underlying method throws).
 */
@SpringBootTest
@Import(AuditAspectIT.AuditTestConfig.class)
class AuditAspectIT {

  private static final UUID ACTOR_ID = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Autowired AuditedTestBean bean;
  @MockBean AuditService auditService;

  @BeforeEach
  void setUpRequestAndAuth() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("203.0.113.42");
    req.addHeader("User-Agent", "junit-test-agent/1.0");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

    UserPrincipal actor =
        new UserPrincipal(
            ACTOR_ID,
            "Test User",
            "test@ccw.test",
            Role.ORG_ADMIN,
            ORG_ID,
            Set.of(),
            Set.of(),
            Set.of(),
            null);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
  }

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
  }

  @Test
  void successWritesOneRow() {
    EventDto result = bean.success();

    verify(auditService, times(1))
        .log(
            eq(ACTOR_ID),
            eq("TEST_ACTION"),
            eq("TEST"),
            eq(result.id()),
            eq("203.0.113.42"),
            eq("junit-test-agent/1.0"),
            any());
  }

  @Test
  void exceptionWritesNoRow() {
    assertThatThrownBy(() -> bean.boom()).isInstanceOf(IllegalStateException.class);
    verify(auditService, never()).log(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void nestedIdFromExpressionResolves() {
    NestedResponse r = bean.nested();
    assertThat(r.event().id()).isNotNull();

    verify(auditService, times(1))
        .log(
            eq(ACTOR_ID),
            eq("EVENT_CREATED"),
            eq("EVENT"),
            eq(r.event().id()),
            any(),
            any(),
            any());
  }

  @Test
  void anonymousCallWritesNullActor() {
    SecurityContextHolder.clearContext();
    EventDto result = bean.success();
    verify(auditService)
        .log(eq(null), eq("TEST_ACTION"), eq("TEST"), eq(result.id()), any(), any(), any());
  }

  @Test
  void unresolvableIdFromGivesNullTargetIdAndStillLogs() {
    bean.unresolvable();
    // The SpEL expression "nope.bogus" can't resolve on the result; the row is still written
    // with target_id = null rather than the request being rolled back.
    verify(auditService).log(eq(ACTOR_ID), eq("WEIRD"), eq("TEST"), eq(null), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Test fixtures
  // ---------------------------------------------------------------------------

  @TestConfiguration
  static class AuditTestConfig {
    @Bean
    AuditedTestBean auditedTestBean() {
      return new AuditedTestBean();
    }
  }

  /** Spring-managed bean exercised by the tests. The aspect intercepts its annotated methods. */
  @Component
  static class AuditedTestBean {

    @Audited(action = "TEST_ACTION", targetType = "TEST")
    public EventDto success() {
      return new EventDto(UUID.randomUUID());
    }

    @Audited(action = "TEST_FAIL", targetType = "TEST")
    public EventDto boom() {
      throw new IllegalStateException("intentional");
    }

    @Audited(action = "EVENT_CREATED", targetType = "EVENT", idFrom = "event.id")
    public NestedResponse nested() {
      return new NestedResponse(new EventDto(UUID.randomUUID()));
    }

    @Audited(action = "WEIRD", targetType = "TEST", idFrom = "nope.bogus")
    public EventDto unresolvable() {
      return new EventDto(UUID.randomUUID());
    }
  }

  record EventDto(UUID id) {}

  record NestedResponse(EventDto event) {}
}
