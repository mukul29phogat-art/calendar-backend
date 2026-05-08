package com.childcarewow.calendar.holiday;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/holidays/approve-batch}. Capped at 100 ids per playbook
 * common-failure-points to prevent accidental DOS — over-cap returns {@code VALIDATION_ERROR}
 * before any work runs.
 */
public record ApproveBatchRequest(@NotEmpty @Size(max = 100) List<UUID> ids) {}
