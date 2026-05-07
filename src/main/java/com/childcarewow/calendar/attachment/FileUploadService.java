package com.childcarewow.calendar.attachment;

import com.childcarewow.calendar.exception.AttachmentInvalidException;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Validates incoming attachment metadata and asks Supabase Storage for a one-shot signed PUT URL
 * (architecture spec § 7.7). Allowlist is JPG and PNG only; max 10 MB. Filenames are sanitized to
 * drop path separators and control characters before being baked into the object key, so a
 * malicious {@code ../etc/passwd} can't escape the {@code {schoolId}/{uuid}/} prefix.
 */
@Service
public class FileUploadService {

  /** Maximum byte size of an upload (10 MB, locked decision § 5.7). */
  public static final long MAX_BYTES = 10L * 1024L * 1024L;

  /** Allowlisted MIME types per locked decision § 5.7. */
  public static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png");

  private final SupabaseStorageClient client;
  private final String bucket;

  public FileUploadService(
      SupabaseStorageClient client,
      @Value("${ccw.attachments.bucket:event-attachments}") String bucket) {
    this.client = client;
    this.bucket = bucket;
  }

  /**
   * Throws {@link AttachmentInvalidException} (mapped to 400 {@code ATTACHMENT_INVALID}) when the
   * MIME type is unsupported or the size is out of range. Returns silently on success.
   */
  public void validate(String mimeType, long sizeBytes) {
    if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType.toLowerCase())) {
      throw new AttachmentInvalidException(
          "Only JPG and PNG attachments are allowed (got: " + mimeType + ")");
    }
    if (sizeBytes <= 0) {
      throw new AttachmentInvalidException("File size must be positive (got: " + sizeBytes + ")");
    }
    if (sizeBytes > MAX_BYTES) {
      throw new AttachmentInvalidException(
          "File too large (" + sizeBytes + " bytes, max " + MAX_BYTES + ")");
    }
  }

  /**
   * Validates, sanitizes the filename, then asks the Supabase client for a signed upload URL. The
   * object key follows {@code {schoolId}/{random uuid}/{sanitizedFilename}}, ensuring a unique path
   * per attachment even if two users upload files with the same name.
   */
  public SignedUploadResult signUpload(
      UUID schoolId, String filename, String mimeType, long sizeBytes) {
    if (schoolId == null) {
      throw new AttachmentInvalidException("schoolId is required");
    }
    validate(mimeType, sizeBytes);
    String safeName = sanitize(filename);
    String objectKey = schoolId + "/" + UUID.randomUUID() + "/" + safeName;
    String uploadUrl = client.createSignedUploadUrl(bucket, objectKey);
    String attachmentUrl = client.publicUrl(bucket, objectKey);
    return new SignedUploadResult(uploadUrl, safeName, attachmentUrl);
  }

  /**
   * Strips path separators ({@code /} and {@code \}) and ASCII control characters from the
   * filename, leaving only printable characters. Throws if the result is blank.
   */
  static String sanitize(String filename) {
    if (filename == null || filename.isBlank()) {
      throw new AttachmentInvalidException("filename is required");
    }
    String stripped = filename.replaceAll("[\\\\/]", "_").replaceAll("\\p{Cntrl}", "");
    if (stripped.isBlank()) {
      throw new AttachmentInvalidException("filename invalid after sanitization");
    }
    return stripped;
  }
}
