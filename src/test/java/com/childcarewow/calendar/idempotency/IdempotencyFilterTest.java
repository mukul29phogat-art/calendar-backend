package com.childcarewow.calendar.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.childcarewow.calendar.auth.Role;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.crosscut.IdempotencyKey;
import com.childcarewow.calendar.crosscut.IdempotencyKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link IdempotencyFilter}. We test the filter directly with Spring's {@code
 * MockHttpServletRequest}/{@code Response} and a mocked repo so the suite stays fast and
 * independent of the full Spring context.
 */
class IdempotencyFilterTest {

  private static final UUID ALICE = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID BOB = UUID.fromString("33333333-0000-0000-0000-000000000002");

  private final IdempotencyKeyRepository repo = mock(IdempotencyKeyRepository.class);
  private final IdempotencyFilter filter = new IdempotencyFilter(repo);

  @BeforeEach
  void authenticateAlice() {
    setActor(ALICE);
  }

  @AfterEach
  void clearAuth() {
    SecurityContextHolder.clearContext();
  }

  // -- pass-through paths -----------------------------------------------------

  @Test
  void passesThroughWhenRouteNotApplicable() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/me");
    request.addHeader(IdempotencyFilter.HEADER, "key-1");
    request.setContent("{}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(eq(request), eq(response));
    verify(repo, never()).findById(any());
    verify(repo, never()).save(any());
  }

  @Test
  void passesThroughForGet() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events");
    request.addHeader(IdempotencyFilter.HEADER, "key-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(eq(request), eq(response));
    verify(repo, never()).findById(any());
  }

  @Test
  void passesThroughWhenHeaderMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(eq(request), eq(response));
    verify(repo, never()).findById(any());
  }

  @Test
  void passesThroughWhenHeaderBlank() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
    request.addHeader(IdempotencyFilter.HEADER, "   ");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(eq(request), eq(response));
    verify(repo, never()).findById(any());
  }

  @Test
  void passesThroughWhenNoAuthenticatedPrincipal() throws Exception {
    SecurityContextHolder.clearContext();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
    request.addHeader(IdempotencyFilter.HEADER, "key-1");
    request.setContent("{}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(eq(request), eq(response));
    verify(repo, never()).findById(any());
  }

  // -- cache hit / miss / replay --------------------------------------------

  @Test
  void firstRequestCachesResponse() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
    request.addHeader(IdempotencyFilter.HEADER, "key-1");
    request.setContent("{\"title\":\"x\"}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();

    when(repo.findById(any())).thenReturn(Optional.empty());
    FilterChain chain =
        (req, res) -> {
          ((HttpServletResponse) res).setStatus(201);
          res.setContentType("application/json");
          res.getWriter().write("{\"id\":\"abc\"}");
          res.getWriter().flush();
        };

    filter.doFilter(request, response, chain);

    ArgumentCaptor<IdempotencyKey> cap = ArgumentCaptor.forClass(IdempotencyKey.class);
    verify(repo, times(1)).save(cap.capture());
    IdempotencyKey saved = cap.getValue();
    assertThat(saved.getStatusCode()).isEqualTo(201);
    assertThat(saved.getResponseBody()).contains("abc");
    assertThat(saved.getRequestHash()).isNotBlank();
    assertThat(saved.getKey()).isNotEqualTo("key-1"); // scoped, not raw
  }

  @Test
  void cachedReplaySameBodyReturnsCachedNoControllerInvocation() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
    request.addHeader(IdempotencyFilter.HEADER, "key-1");
    request.setContent("{\"title\":\"x\"}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();

    String bodyHash = IdempotencyFilter.sha256Hex("{\"title\":\"x\"}".getBytes());
    IdempotencyKey cached = new IdempotencyKey();
    cached.setKey("scoped");
    cached.setRequestHash(bodyHash);
    cached.setResponseBody("{\"id\":\"cached-1\"}");
    cached.setStatusCode(201);
    when(repo.findById(any())).thenReturn(Optional.of(cached));

    FilterChain chain = mock(FilterChain.class);
    filter.doFilter(request, response, chain);

    verify(chain, never()).doFilter(any(), any());
    verify(repo, never()).save(any());
    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"cached-1\"}");
  }

  @Test
  void replayWithDifferentBodyReturns409() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
    request.addHeader(IdempotencyFilter.HEADER, "key-1");
    request.setContent("{\"title\":\"NEW\"}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();

    IdempotencyKey cached = new IdempotencyKey();
    cached.setKey("scoped");
    cached.setRequestHash(IdempotencyFilter.sha256Hex("{\"title\":\"OLD\"}".getBytes()));
    cached.setResponseBody("{\"id\":\"cached-1\"}");
    cached.setStatusCode(201);
    when(repo.findById(any())).thenReturn(Optional.of(cached));

    FilterChain chain = mock(FilterChain.class);
    filter.doFilter(request, response, chain);

    verify(chain, never()).doFilter(any(), any());
    verify(repo, never()).save(any());
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getContentAsString()).contains("IDEMPOTENCY_REPLAY");
  }

  @Test
  void crossUserIsolationDifferentScopedKeys() throws Exception {
    // Alice + same client key
    setActor(ALICE);
    MockHttpServletRequest aliceReq = new MockHttpServletRequest("POST", "/api/v1/events");
    aliceReq.addHeader(IdempotencyFilter.HEADER, "shared-key");
    aliceReq.setContent("{\"title\":\"x\"}".getBytes());
    MockHttpServletResponse aliceResp = new MockHttpServletResponse();
    when(repo.findById(any())).thenReturn(Optional.empty());
    FilterChain chain1 =
        (req, res) -> {
          ((HttpServletResponse) res).setStatus(201);
          res.getWriter().write("{}");
          res.getWriter().flush();
        };
    filter.doFilter(aliceReq, aliceResp, chain1);

    ArgumentCaptor<IdempotencyKey> capAlice = ArgumentCaptor.forClass(IdempotencyKey.class);
    verify(repo, times(1)).save(capAlice.capture());
    String aliceScopedKey = capAlice.getValue().getKey();

    // Bob + same client key
    setActor(BOB);
    MockHttpServletRequest bobReq = new MockHttpServletRequest("POST", "/api/v1/events");
    bobReq.addHeader(IdempotencyFilter.HEADER, "shared-key");
    bobReq.setContent("{\"title\":\"x\"}".getBytes());
    MockHttpServletResponse bobResp = new MockHttpServletResponse();
    FilterChain chain2 =
        (req, res) -> {
          ((HttpServletResponse) res).setStatus(201);
          res.getWriter().write("{}");
          res.getWriter().flush();
        };
    filter.doFilter(bobReq, bobResp, chain2);

    ArgumentCaptor<IdempotencyKey> capBob = ArgumentCaptor.forClass(IdempotencyKey.class);
    verify(repo, times(2)).save(capBob.capture()); // 2 total saves across both actors
    String bobScopedKey = capBob.getValue().getKey();

    assertThat(bobScopedKey)
        .as("same client key under different actors must scope to different rows")
        .isNotEqualTo(aliceScopedKey);
  }

  @Test
  void persistFailureSwallowedNotPropagatedToClient() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
    request.addHeader(IdempotencyFilter.HEADER, "key-1");
    request.setContent("{}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(repo.findById(any())).thenReturn(Optional.empty());
    when(repo.save(any())).thenThrow(new RuntimeException("DB down"));

    FilterChain chain =
        (req, res) -> {
          ((HttpServletResponse) res).setStatus(201);
          res.getWriter().write("{\"id\":\"x\"}");
          res.getWriter().flush();
        };

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentAsString()).contains("\"id\":\"x\"");
  }

  @Test
  void nonSuccessStatusNotCached() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
    request.addHeader(IdempotencyFilter.HEADER, "key-1");
    request.setContent("{}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(repo.findById(any())).thenReturn(Optional.empty());

    FilterChain chain =
        (req, res) -> {
          ((HttpServletResponse) res).setStatus(400);
          res.getWriter().write("{\"error\":\"bad\"}");
          res.getWriter().flush();
        };

    filter.doFilter(request, response, chain);

    verify(repo, never()).save(any());
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void shouldApplyMatchesAllowlistedRoutes() {
    assertThat(IdempotencyFilter.shouldApply(post("/api/v1/events"))).isTrue();
    assertThat(IdempotencyFilter.shouldApply(post("/api/v1/tasks"))).isTrue();
    assertThat(IdempotencyFilter.shouldApply(post("/api/v1/holidays"))).isTrue();
    assertThat(IdempotencyFilter.shouldApply(post("/api/v1/important-dates"))).isTrue();
    assertThat(IdempotencyFilter.shouldApply(post("/api/v1/attachments/sign-upload"))).isTrue();
    assertThat(IdempotencyFilter.shouldApply(post("/api/v1/auth/me"))).isFalse();
    assertThat(IdempotencyFilter.shouldApply(post("/api/v1/tasks/abc/status"))).isTrue();
  }

  @Test
  void shouldApplyRejectsNonPostMethods() {
    MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/events");
    MockHttpServletRequest patch = new MockHttpServletRequest("PATCH", "/api/v1/events");
    assertThat(IdempotencyFilter.shouldApply(get)).isFalse();
    assertThat(IdempotencyFilter.shouldApply(patch)).isFalse();
  }

  @Test
  void sha256HexIsStable() {
    String a = IdempotencyFilter.sha256Hex("hello".getBytes());
    String b = IdempotencyFilter.sha256Hex("hello".getBytes());
    String c = IdempotencyFilter.sha256Hex("HELLO".getBytes());
    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(c);
    assertThat(a).hasSize(64); // 32 bytes hex
  }

  // -- helpers --------------------------------------------------------------

  private static void setActor(UUID id) {
    SecurityContextHolder.clearContext();
    UserPrincipal user =
        new UserPrincipal(
            id,
            "Test",
            "test@ccw.test",
            Role.ORG_ADMIN,
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Set.of(),
            Set.of(),
            Set.of(),
            null);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
  }

  private static MockHttpServletRequest post(String path) {
    return new MockHttpServletRequest("POST", path);
  }

  // Suppresses an IOException-throws warning on the chain (the lambda above).
  @SuppressWarnings("unused")
  private static void _hint() throws IOException {}
}
