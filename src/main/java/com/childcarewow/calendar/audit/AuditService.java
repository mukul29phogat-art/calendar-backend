package com.childcarewow.calendar.audit;

import com.childcarewow.calendar.crosscut.AuditEvent;
import com.childcarewow.calendar.crosscut.AuditEventRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only writer for {@code audit_events}. Used by:
 *
 * <ul>
 *   <li>{@link AuditAspect} — for HTTP-driven actions annotated with {@link Audited}.
 *   <li>Non-HTTP code (scheduled jobs, post-payment hooks, etc.) — call {@link #log} directly.
 * </ul>
 *
 * <p>The {@code audit_events} table is application-layer immutable: nothing in {@code src/main}
 * calls {@code save} on existing rows or {@code delete*}, and Part 3.4 will add Hibernate
 * {@code @Immutable} on the entity to enforce that at the JPA level.
 */
@Service
public class AuditService {

  private final AuditEventRepository repo;

  public AuditService(AuditEventRepository repo) {
    this.repo = repo;
  }

  /**
   * Inserts an audit row. Runs in a {@link Propagation#REQUIRES_NEW} transaction so audit failures
   * cannot roll back the user-facing transaction (and conversely, a rolled-back business txn still
   * leaves the audit record committed).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void log(
      UUID actorUserId,
      String action,
      String targetType,
      UUID targetId,
      String ipAddress,
      String userAgent,
      Map<String, Object> metadata) {
    AuditEvent e = new AuditEvent();
    e.setActorUserId(actorUserId);
    e.setAction(action);
    e.setTargetType(targetType);
    e.setTargetId(targetId);
    e.setIpAddress(ipAddress);
    e.setUserAgent(userAgent);
    e.setMetadata(metadata == null ? Map.of() : metadata);
    repo.save(e);
  }
}
