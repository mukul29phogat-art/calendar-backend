package com.childcarewow.calendar.attachment;

/**
 * Thin abstraction over Supabase Storage's signed-upload + public-URL endpoints. Implemented by
 * {@link SupabaseStorageClientImpl} for production; mocked in unit tests so the {@link
 * FileUploadService} can be exercised without Supabase in the loop.
 */
public interface SupabaseStorageClient {

  /**
   * Asks Supabase Storage for a one-shot signed PUT URL the FE can upload to. Returns the absolute
   * URL (including the {@code token} query parameter).
   */
  String createSignedUploadUrl(String bucket, String objectKey);

  /**
   * Returns the eventual canonical URL the file will be served from once uploaded. For private
   * buckets this is a placeholder until the read path issues its own signed download URL; for
   * public buckets this is directly fetchable.
   */
  String publicUrl(String bucket, String objectKey);
}
