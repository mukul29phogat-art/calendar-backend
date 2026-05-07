package com.childcarewow.calendar.audit;

import com.childcarewow.calendar.auth.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes one row to {@code audit_events} per successful invocation of a method annotated with
 * {@link AuditRead}. The row's {@code metadata.subject_ids} is the full UUID set surfaced in the
 * response (extracted via the SpEL expression on {@link AuditRead#subjectsFrom()}); the row is
 * <b>not</b> per-subject — see the annotation Javadoc for the rationale.
 *
 * <p><b>Failure handling.</b> Any internal failure (SpEL didn't resolve, audit write threw,
 * security/request context missing) is swallowed at WARN — read audits must never fail the
 * user-facing read.
 */
@Aspect
@Component
public class AuditReadAspect {

  private static final Logger log = LoggerFactory.getLogger(AuditReadAspect.class);
  private static final SpelExpressionParser SPEL = new SpelExpressionParser();

  private final AuditService auditService;

  public AuditReadAspect(AuditService auditService) {
    this.auditService = auditService;
  }

  @AfterReturning(
      pointcut = "@annotation(audit)",
      returning = "result",
      argNames = "jp,audit,result")
  public void onSuccess(JoinPoint jp, AuditRead audit, Object result) {
    try {
      // Sample-rate gate. nextInt(100) returns 0..99 inclusive; sampleRate=100 always passes.
      if (ThreadLocalRandom.current().nextInt(100) >= audit.sampleRate()) {
        return;
      }

      List<UUID> subjectIds = resolveSubjectIds(audit.subjectsFrom(), result);
      UUID actorId = resolveActorId();
      HttpRequestInfo http = resolveHttp();

      auditService.log(
          actorId,
          audit.action(),
          "STUDENT",
          null,
          http.ipAddress(),
          http.userAgent(),
          Map.of("subject_ids", subjectIds));
    } catch (RuntimeException ex) {
      log.warn(
          "Read-audit write failed for action={} on {}.{}",
          audit.action(),
          jp.getSignature().getDeclaringType().getSimpleName(),
          jp.getSignature().getName(),
          ex);
    }
  }

  static List<UUID> resolveSubjectIds(String spelExpr, Object result) {
    if (result == null || spelExpr == null || spelExpr.isBlank()) {
      return List.of();
    }
    try {
      Object value = SPEL.parseExpression(spelExpr).getValue(new StandardEvaluationContext(result));
      if (value == null) {
        return List.of();
      }
      List<UUID> out = new ArrayList<>();
      if (value instanceof Collection<?> coll) {
        for (Object item : coll) {
          if (item instanceof UUID u) {
            out.add(u);
          } else if (item instanceof String s) {
            try {
              out.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
              // Skip values that aren't UUIDs; we'd rather have a partial audit than a
              // failure-on-bad-data.
            }
          }
        }
      }
      return out;
    } catch (RuntimeException ex) {
      log.debug("AuditRead subjectsFrom='{}' failed to resolve on result {}", spelExpr, result, ex);
      return List.of();
    }
  }

  private static UUID resolveActorId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || auth.getPrincipal() == null) {
      return null;
    }
    if (auth.getPrincipal() instanceof UserPrincipal up) {
      return up.id();
    }
    return null;
  }

  private static HttpRequestInfo resolveHttp() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes sra) {
      HttpServletRequest req = sra.getRequest();
      return new HttpRequestInfo(req.getRemoteAddr(), req.getHeader("User-Agent"));
    }
    return new HttpRequestInfo(null, null);
  }

  private record HttpRequestInfo(String ipAddress, String userAgent) {}
}
