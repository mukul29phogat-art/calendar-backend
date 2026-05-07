package com.childcarewow.calendar.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

/**
 * Standard error envelope. {@code ok=false} so the FE's discriminated union narrows immediately;
 * {@code traceId} is the per-request UUID set by {@link TraceIdFilter}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceErrorResponse(boolean ok, ServiceError error, String traceId) {

  public static ServiceErrorResponse of(String code, String message, String field) {
    return new ServiceErrorResponse(
        false, new ServiceError(code, message, field), MDC.get("traceId"));
  }
}
