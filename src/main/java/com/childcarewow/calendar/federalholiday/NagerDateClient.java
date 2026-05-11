package com.childcarewow.calendar.federalholiday;

import java.util.List;

/**
 * Reads public-holiday data from Nager.Date. Sole consumer is {@link FederalHolidaySyncJob}.
 *
 * <p>Defined as an interface so the sync job can be exercised against a mock in integration tests
 * without standing up an HTTP fixture. The production wiring is {@link NagerDateClientImpl}.
 */
public interface NagerDateClient {

  /**
   * Fetches the public holidays for {@code year} in {@code countryCode} (ISO-3166-1 alpha-2, e.g.
   * {@code US}). Implementations should throw a {@link RuntimeException} on network or 5xx errors —
   * the sync job catches per fetch, logs, and continues with other (year, country) groups.
   */
  List<NagerHoliday> fetchPublicHolidays(int year, String countryCode);
}
