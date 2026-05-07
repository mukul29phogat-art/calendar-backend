package com.childcarewow.calendar.attachment;

/**
 * Response shape for {@code POST /api/v1/attachments/sign-upload}.
 *
 * <p>{@code uploadUrl} is the time-limited signed PUT URL the FE uses to upload the bytes. {@code
 * attachmentName} is the sanitized filename (after {@link FileUploadService#sanitize}); the FE
 * should display this rather than echoing the user's original input. {@code attachmentUrl} is the
 * canonical URL the file will be reachable at once uploaded.
 */
public record SignedUploadResult(String uploadUrl, String attachmentName, String attachmentUrl) {}
