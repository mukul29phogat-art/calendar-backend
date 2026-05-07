package com.childcarewow.calendar.audit;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a controller method whose successful invocation should be recorded as a <i>read</i> audit
 * event in {@code audit_events}. Distinct from {@link Audited} (which records writes keyed by a
 * single {@code target_id}) — read audits are COPPA-driven: they capture <b>which children's data
 * was queried</b>, batched as a single row per request with the full UUID set under {@code
 * metadata.subject_ids}.
 *
 * <p><b>One row per request.</b> A single calendar-window read might surface dozens of children's
 * data; we still write exactly one audit row, with the full UUID list in metadata. Storing one row
 * per child would 100x the write volume on the calendar read path for no compliance gain.
 *
 * <p><b>Sample rate.</b> {@link #sampleRate()} is a percentage in {@code [0, 100]}. The default
 * (100) audits every read. Production may dial it down to e.g. 25% for high-frequency selector
 * endpoints; compliance review must approve any rate < 100.
 *
 * <p><b>Where to apply.</b> Same as {@link Audited}: on {@code @RestController} methods only.
 * Spring AOP doesn't intercept self-calls; service-layer reads should call {@link AuditService#log}
 * directly if they need recording.
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface AuditRead {

  /** The audit action name, e.g. {@code "STUDENT_VIEW"}. */
  String action();

  /**
   * SpEL expression evaluated against the controller's return value. Must yield a {@code
   * Collection<UUID>} or {@code Collection<String>}. Examples:
   *
   * <ul>
   *   <li>{@code "![*.id]"} when the response is a {@code List<StudentView>}.
   *   <li>{@code "students.![id]"} when the response is a wrapper with a {@code students} field.
   * </ul>
   */
  String subjectsFrom();

  /** Percentage of invocations to audit. Default 100 (every read). */
  int sampleRate() default 100;
}
