package com.childcarewow.calendar.audit;

import com.childcarewow.calendar.auth.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
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
 * Writes one row to {@code audit_events} for every successful invocation of a method annotated with
 * {@link Audited}. Failures (any thrown exception) write nothing — the {@code @AfterReturning}
 * advice does not fire on exceptions.
 *
 * <p><b>Self-invocation caveat.</b> Spring AOP uses a runtime proxy; calls from within the same
 * class bypass the proxy. Annotate at the controller layer only.
 *
 * <p><b>Resolution failures are non-fatal.</b> If the SecurityContext is empty (anonymous), the
 * RequestContext is empty (called outside an HTTP scope), or SpEL on {@code idFrom} fails, the
 * aspect logs at WARN and inserts an audit row with whatever fields it could resolve (typically
 * {@code target_id = null}). The user-facing request is never rolled back by an audit failure.
 */
@Aspect
@Component
public class AuditAspect {

  private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
  private static final SpelExpressionParser SPEL = new SpelExpressionParser();

  private final AuditService auditService;

  public AuditAspect(AuditService auditService) {
    this.auditService = auditService;
  }

  @AfterReturning(
      pointcut = "@annotation(audited)",
      returning = "result",
      argNames = "jp,audited,result")
  public void onSuccess(JoinPoint jp, Audited audited, Object result) {
    try {
      UUID actorId = resolveActorId();
      HttpRequestInfo http = resolveHttp();
      UUID targetId = resolveTargetId(audited.idFrom(), result);
      auditService.log(
          actorId,
          audited.action(),
          audited.targetType(),
          targetId,
          http.ipAddress(),
          http.userAgent(),
          Map.of());
    } catch (RuntimeException ex) {
      // Audit failures must never propagate. Log at WARN so ops sees them but the user response
      // continues.
      log.warn(
          "Audit write failed for action={} on {}.{}",
          audited.action(),
          jp.getSignature().getDeclaringType().getSimpleName(),
          jp.getSignature().getName(),
          ex);
    }
  }

  private static UUID resolveActorId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || auth.getPrincipal() == null) {
      return null;
    }
    Object principal = auth.getPrincipal();
    if (principal instanceof UserPrincipal up) {
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

  private static UUID resolveTargetId(String idFrom, Object result) {
    if (idFrom == null || idFrom.isEmpty() || result == null) {
      return null;
    }
    try {
      StandardEvaluationContext ctx = new StandardEvaluationContext(result);
      Object value = SPEL.parseExpression(idFrom).getValue(ctx);
      if (value instanceof UUID u) {
        return u;
      }
      if (value instanceof String s) {
        return UUID.fromString(s);
      }
      return null;
    } catch (RuntimeException ex) {
      log.debug("Audit idFrom='{}' did not resolve on result {}", idFrom, result, ex);
      return null;
    }
  }

  private record HttpRequestInfo(String ipAddress, String userAgent) {}
}
