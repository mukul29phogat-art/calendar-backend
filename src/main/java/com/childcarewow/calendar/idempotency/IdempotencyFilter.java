package com.childcarewow.calendar.idempotency;

import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.crosscut.IdempotencyKey;
import com.childcarewow.calendar.crosscut.IdempotencyKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotency middleware (architecture spec § 7.9). Listens for {@code Idempotency-Key} headers on
 * the listed POST creates and either short-circuits with the cached response (replay) or wraps the
 * downstream response so the controller's output gets persisted for 24 h.
 *
 * <p><b>Cross-user isolation.</b> The cache key actually persisted is {@code SHA-256(actor.id() +
 * ":" + clientIdempotencyKey)}, so two clients using the same UUID see independent entries. See
 * {@code idempotency/README.md} for the rationale and the threat model.
 *
 * <p><b>Pass-through cases</b> (filter does nothing, request continues normally):
 *
 * <ul>
 *   <li>Method/path not on the {@link #APPLICABLE_PATHS} allowlist.
 *   <li>Missing or blank {@code Idempotency-Key} header.
 *   <li>No authenticated principal (the auth filter would normally have rejected; if not, let later
 *       filters handle).
 * </ul>
 *
 * <p><b>Replay handling.</b>
 *
 * <ul>
 *   <li>Same scoped key + same request hash → return cached status + body (no DB writes).
 *   <li>Same scoped key + different hash → 409 IDEMPOTENCY_REPLAY (envelope shape per § 15).
 *   <li>No cached entry → run the downstream handler, persist 2xx responses keyed by the scoped
 *       key.
 * </ul>
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

  /** Routes the filter applies to. Other POSTs pass through unchanged. */
  static final List<String> APPLICABLE_PATHS =
      List.of(
          "/api/v1/events",
          "/api/v1/tasks",
          "/api/v1/holidays",
          "/api/v1/important-dates",
          "/api/v1/attachments/sign-upload");

  static final String HEADER = "Idempotency-Key";

  private final IdempotencyKeyRepository repo;

  public IdempotencyFilter(IdempotencyKeyRepository repo) {
    this.repo = repo;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    if (!shouldApply(request)) {
      chain.doFilter(request, response);
      return;
    }

    String clientKey = request.getHeader(HEADER);
    if (clientKey == null || clientKey.isBlank()) {
      chain.doFilter(request, response);
      return;
    }

    UUID actorId = currentActorId();
    if (actorId == null) {
      // No principal — let auth filter reject downstream, don't try to scope.
      chain.doFilter(request, response);
      return;
    }

    // Read the body from the ORIGINAL request stream first, then wrap so downstream
    // consumers (the controller) can re-read from the cached bytes. Reading from the
    // wrapper before caching would yield an empty array — see the BodyCachingRequestWrapper
    // Javadoc.
    byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
    BodyCachingRequestWrapper req = new BodyCachingRequestWrapper(request);
    req.setCachedBody(body);
    String requestHash = sha256Hex(body);
    String scopedKey = sha256Hex((actorId + ":" + clientKey).getBytes(StandardCharsets.UTF_8));

    Optional<IdempotencyKey> cached = repo.findById(scopedKey);
    if (cached.isPresent()) {
      IdempotencyKey row = cached.get();
      if (!row.getRequestHash().equals(requestHash)) {
        writeReplayConflict(response);
        return;
      }
      writeCached(response, row);
      return;
    }

    ContentCachingResponseWrapper resp = new ContentCachingResponseWrapper(response);
    chain.doFilter(req, resp);

    int status = resp.getStatus();
    if (status >= 200 && status < 300) {
      try {
        String responseBody = new String(resp.getContentAsByteArray(), StandardCharsets.UTF_8);
        IdempotencyKey row = new IdempotencyKey();
        row.setKey(scopedKey);
        row.setRequestHash(requestHash);
        row.setResponseBody(responseBody);
        row.setStatusCode(status);
        repo.save(row);
      } catch (RuntimeException ex) {
        // Persisting the cache must never fail the request the user already saw succeed.
        log.warn("Idempotency cache persist failed for scopedKey={}", scopedKey, ex);
      }
    }
    resp.copyBodyToResponse();
  }

  // -- helpers --------------------------------------------------------------

  static boolean shouldApply(HttpServletRequest request) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return false;
    }
    String path = request.getRequestURI();
    if (path == null) {
      return false;
    }
    for (String prefix : APPLICABLE_PATHS) {
      if (path.equals(prefix) || path.startsWith(prefix + "/")) {
        return true;
      }
    }
    return false;
  }

  private static UUID currentActorId() {
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

  static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static void writeCached(HttpServletResponse response, IdempotencyKey row)
      throws IOException {
    response.setStatus(row.getStatusCode());
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    try (PrintWriter w = response.getWriter()) {
      w.write(row.getResponseBody());
    }
  }

  private static void writeReplayConflict(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_CONFLICT);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String body =
        "{\"ok\":false,\"error\":{\"code\":\"IDEMPOTENCY_REPLAY\","
            + "\"message\":\"Idempotency-Key replayed with a different request body\"},"
            + "\"traceId\":null}";
    try (PrintWriter w = response.getWriter()) {
      w.write(body);
    }
  }
}
