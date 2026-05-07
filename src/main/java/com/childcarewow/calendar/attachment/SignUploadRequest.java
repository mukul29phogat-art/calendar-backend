package com.childcarewow.calendar.attachment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/** Request body for {@code POST /api/v1/attachments/sign-upload}. */
public record SignUploadRequest(
    @NotNull UUID schoolId,
    @NotBlank String filename,
    @NotBlank String mimeType,
    @Positive long sizeBytes) {}
