package com.childcarewow.calendar.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link GlobalExceptionHandler}. Each {@link ServiceException} subclass is
 * exercised via the single shared handler. The Bean-validation handler and the unknown-exception
 * handler each get their own test.
 *
 * <p>We test the handler directly rather than via {@code @WebMvcTest} so the suite stays fast and
 * doesn't require a full Spring context per case.
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @BeforeEach
  void putTraceId() {
    MDC.put("traceId", "test-trace-1234");
  }

  @AfterEach
  void clearMdc() {
    MDC.remove("traceId");
  }

  @Test
  void validationExceptionMapsTo400() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(new ValidationException("title", "must not be blank"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().ok()).isFalse();
    assertThat(r.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
    assertThat(r.getBody().error().field()).isEqualTo("title");
    assertThat(r.getBody().error().message()).isEqualTo("must not be blank");
    assertThat(r.getBody().traceId()).isEqualTo("test-trace-1234");
  }

  @Test
  void eventOnHolidayMapsTo409() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(new EventOnHolidayException("Memorial Day"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(r.getBody().error().code()).isEqualTo("EVENT_ON_HOLIDAY");
    assertThat(r.getBody().error().field()).isEqualTo("startDt");
  }

  @Test
  void taskOnHolidayMapsTo409() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(new TaskOnHolidayException("July 4th"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(r.getBody().error().code()).isEqualTo("TASK_ON_HOLIDAY");
  }

  @Test
  void invalidTimeRangeMapsTo400() {
    ResponseEntity<ServiceErrorResponse> r = handler.handleService(new InvalidTimeRangeException());
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().error().code()).isEqualTo("INVALID_TIME_RANGE");
  }

  @Test
  void invalidRecurrenceMapsTo400() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(new InvalidRecurrenceException("dueDayOfWeek required for WEEKLY"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().error().code()).isEqualTo("INVALID_RECURRENCE");
  }

  @Test
  void attachmentInvalidMapsTo400() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(new AttachmentInvalidException("file too large (>10 MB)"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().error().code()).isEqualTo("ATTACHMENT_INVALID");
  }

  @Test
  void duplicateHolidayMapsTo409() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(new DuplicateHolidayException(LocalDate.of(2026, 12, 25)));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(r.getBody().error().code()).isEqualTo("DUPLICATE_HOLIDAY");
  }

  @Test
  void forbiddenMapsTo403() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(new ForbiddenException("event.create"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(r.getBody().error().code()).isEqualTo("FORBIDDEN");
  }

  @Test
  void notFoundMapsTo404() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(new NotFoundException("Event", UUID.randomUUID()));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(r.getBody().error().code()).isEqualTo("NOT_FOUND");
  }

  @Test
  void idempotencyReplayMapsTo409() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(new IdempotencyReplayException());
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(r.getBody().error().code()).isEqualTo("IDEMPOTENCY_REPLAY");
  }

  @Test
  void platformUnavailableMapsTo503() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleService(
            new PlatformUnavailableException("Platform DB unreachable", new RuntimeException()));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(r.getBody().error().code()).isEqualTo("PLATFORM_UNAVAILABLE");
  }

  @Test
  void unknownExceptionMapsTo500WithoutLeakingMessage() {
    ResponseEntity<ServiceErrorResponse> r =
        handler.handleUnknown(new NullPointerException("internal db pool ref was null"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(r.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
    // The ORIGINAL message must NOT leak — sanitized to a generic string
    assertThat(r.getBody().error().message()).isEqualTo("An unexpected error occurred");
    assertThat(r.getBody().error().message()).doesNotContain("internal db pool ref was null");
  }
}
