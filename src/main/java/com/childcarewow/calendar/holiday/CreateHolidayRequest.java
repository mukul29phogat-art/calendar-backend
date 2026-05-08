package com.childcarewow.calendar.holiday;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/holidays} (CUSTOM only — federal holidays land via the
 * Series 6.4 Nager.Date sync job, not this endpoint).
 */
public record CreateHolidayRequest(
    @NotNull UUID schoolId,
    @NotNull LocalDate date,
    @NotBlank @Size(max = 120) String name,
    String notes) {}
