package com.childcarewow.calendar.exception;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Generates a per-request UUID and stores it in {@link MDC} as {@code traceId} so log lines and
 * {@link ServiceErrorResponse} envelopes can correlate. Also echoed back as the {@code trace-id}
 * response header (allow-listed in {@code SecurityConfig}'s exposed headers).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  static final String TRACE_ID_KEY = "traceId";
  static final String TRACE_ID_HEADER = "trace-id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = UUID.randomUUID().toString();
    MDC.put(TRACE_ID_KEY, traceId);
    response.setHeader(TRACE_ID_HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(TRACE_ID_KEY);
    }
  }
}
