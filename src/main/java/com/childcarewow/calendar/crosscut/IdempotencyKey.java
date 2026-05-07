package com.childcarewow.calendar.crosscut;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

  @Id private String key;

  @Column(name = "request_hash", nullable = false)
  private String requestHash;

  // JSONB cached response. Stored/read as a JSON string; service code (de)serializes.
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", columnDefinition = "jsonb", nullable = false)
  private String responseBody;

  @Column(name = "status_code", nullable = false)
  private int statusCode;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  // expires_at defaults to now() + 24h via the DB DEFAULT. Mark insertable=false so
  // Hibernate doesn't send it in INSERT when not explicitly set.
  @Column(name = "expires_at", insertable = false, updatable = false)
  private OffsetDateTime expiresAt;

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public void setRequestHash(String requestHash) {
    this.requestHash = requestHash;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public void setResponseBody(String responseBody) {
    this.responseBody = responseBody;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public void setStatusCode(int statusCode) {
    this.statusCode = statusCode;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }
}
