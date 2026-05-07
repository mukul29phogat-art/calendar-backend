package com.childcarewow.calendar.audit;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a controller method whose successful invocation should be recorded in {@code audit_events}.
 *
 * <p><b>Where to apply.</b> On {@code @RestController} methods only — the AOP aspect runs as a
 * proxy and does not intercept self-calls within the same class. Service-layer code that needs to
 * audit (scheduled jobs, etc.) should call {@link AuditService#log} directly.
 *
 * <p>The recorded row is keyed by:
 *
 * <ul>
 *   <li>{@link #action()} — required, e.g. {@code "EVENT_CREATED"}.
 *   <li>{@link #targetType()} — optional, e.g. {@code "EVENT"}.
 *   <li>{@link #idFrom()} — SpEL expression evaluated against the method's return value to extract
 *       the target row's UUID. Defaults to {@code "id"} (i.e. {@code result.id}). Set to an empty
 *       string to skip ID resolution.
 * </ul>
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface Audited {

  String action();

  String targetType() default "";

  String idFrom() default "id";
}
