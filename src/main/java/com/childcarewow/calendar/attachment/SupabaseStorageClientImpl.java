package com.childcarewow.calendar.attachment;

import com.childcarewow.calendar.exception.AttachmentInvalidException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Production implementation of {@link SupabaseStorageClient} that calls Supabase Storage's
 * signed-upload endpoint. Reads {@code ccw.supabase.url} and {@code ccw.supabase.service-role-key}
 * from configuration — these resolve to LocalStack secrets in dev and to Secrets Manager in
 * staging/prod (wired up in Series 11).
 *
 * <p>Wire format (Supabase Storage v3):
 *
 * <ul>
 *   <li>Request: {@code POST {SUPABASE_URL}/storage/v1/object/upload/sign/{bucket}/{objectKey}}
 *       with {@code Authorization: Bearer {SERVICE_ROLE_KEY}}.
 *   <li>Response: {@code { "url": "/object/upload/sign/.../?token=...", "token": "..." }}.
 *   <li>Client uses the absolute version of {@code response.url} as the PUT URL.
 * </ul>
 */
@Component
public class SupabaseStorageClientImpl implements SupabaseStorageClient {

  private static final Logger log = LoggerFactory.getLogger(SupabaseStorageClientImpl.class);

  private final RestClient http;
  private final String supabaseUrl;

  public SupabaseStorageClientImpl(
      @Value("${ccw.supabase.url:}") String supabaseUrl,
      @Value("${ccw.supabase.service-role-key:}") String serviceRoleKey) {
    this.supabaseUrl = supabaseUrl;
    this.http =
        RestClient.builder()
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceRoleKey)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
  }

  @Override
  public String createSignedUploadUrl(String bucket, String objectKey) {
    if (supabaseUrl == null || supabaseUrl.isBlank()) {
      // Defensive: in dev with LocalStack we don't want a 500 when this is unconfigured. The
      // attachment-controller integration test (gated @Tag("integration")) is the one that
      // requires real Supabase wiring; everyday dev hits the mocked client.
      throw new AttachmentInvalidException(
          "Supabase URL is not configured (ccw.supabase.url) — sign-upload unavailable");
    }
    String endpoint = supabaseUrl + "/storage/v1/object/upload/sign/" + bucket + "/" + objectKey;
    @SuppressWarnings("unchecked")
    Map<String, Object> resp = http.post().uri(endpoint).retrieve().body(Map.class);
    if (resp == null || resp.get("url") == null) {
      log.warn("Supabase signed-upload response missing url field for {}", objectKey);
      throw new AttachmentInvalidException("Sign-upload failed: empty response");
    }
    String relative = String.valueOf(resp.get("url"));
    return supabaseUrl + (relative.startsWith("/") ? relative : "/" + relative);
  }

  @Override
  public String publicUrl(String bucket, String objectKey) {
    return supabaseUrl + "/storage/v1/object/public/" + bucket + "/" + objectKey;
  }
}
