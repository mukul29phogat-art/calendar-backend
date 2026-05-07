package com.childcarewow.calendar.crosscut;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Insert-only audit log for COPPA compliance. {@link Immutable} causes Hibernate to silently
 * suppress {@code UPDATE} statements for managed entities of this type — modifications via {@code
 * save(...)} on a previously-persisted row are not flushed to the database. Inserts and {@code
 * SELECT} continue to work normally; this annotation does <i>not</i> block deletes (no code path in
 * {@code src/main} issues one, and CI has a grep guard against {@code .save}/{@code .delete} on the
 * typed {@code AuditEventRepository} variable name).
 *
 * <p>Defense in depth: the schema also permits the operations, so a native SQL bypass would still
 * succeed. See {@code docs/security/audit-immutability.md}.
 */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(nullable = false)
  private String action;

  @Column(name = "target_type")
  private String targetType;

  @Column(name = "target_id")
  private UUID targetId;

  // inet column. Stored/read as a String; service code parses to InetAddress as needed.
  // Postgres needs the literal cast `?::inet` on write — Hibernate would otherwise send VARCHAR
  // and fail with `column "ip_address" is of type inet but expression is of type character
  // varying`.
  @Column(name = "ip_address", columnDefinition = "inet")
  @ColumnTransformer(write = "?::inet")
  private String ipAddress;

  @Column(name = "user_agent")
  private String userAgent;

  // metadata jsonb — must be Map<String,Object> not String per playbook
  // Common Failure Points: a String + jsonb mapping fails with
  // "column metadata is of type jsonb but expression is of type bytea".
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public void setActorUserId(UUID actorUserId) {
    this.actorUserId = actorUserId;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getTargetType() {
    return targetType;
  }

  public void setTargetType(String targetType) {
    this.targetType = targetType;
  }

  public UUID getTargetId() {
    return targetId;
  }

  public void setTargetId(UUID targetId) {
    this.targetId = targetId;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
