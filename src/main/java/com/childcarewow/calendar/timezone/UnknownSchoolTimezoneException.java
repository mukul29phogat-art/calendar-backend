package com.childcarewow.calendar.timezone;

import com.childcarewow.calendar.exception.NotFoundException;
import java.util.UUID;

/**
 * Raised when {@link TimezoneService#zoneFor(UUID)} can't return a usable {@link java.time.ZoneId}:
 * the school doesn't exist, has a null/blank timezone column, or stores a malformed IANA name.
 * Surfaces as 404 via the {@link NotFoundException} parent's HTTP mapping.
 *
 * <p>The {@code detail} field carries the specific reason for logging; the underlying cause (e.g.
 * {@code DateTimeException}) is logged at WARN inside {@link TimezoneService} so it isn't lost even
 * though the exception itself doesn't carry it (the existing {@code NotFoundException} constructor
 * doesn't accept a cause, and we'd rather not introduce a {@code this}-escape via {@code
 * initCause()} in this constructor).
 */
public class UnknownSchoolTimezoneException extends NotFoundException {

  private static final long serialVersionUID = 1L;

  private final String detail;

  public UnknownSchoolTimezoneException(UUID schoolId, String detail, Throwable cause) {
    super("School", schoolId);
    this.detail = detail;
    // cause intentionally dropped — see Javadoc.
  }

  public String getDetail() {
    return detail;
  }
}
