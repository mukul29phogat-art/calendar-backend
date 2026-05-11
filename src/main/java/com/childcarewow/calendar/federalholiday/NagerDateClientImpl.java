package com.childcarewow.calendar.federalholiday;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient-backed {@link NagerDateClient}. The host is <b>hardcoded</b> rather than config-driven
 * on purpose: an attacker who can flip a config value (Spring Cloud AWS Secrets Manager rotation,
 * environment override, etc.) could otherwise redirect this scheduled fetch at an internal endpoint
 * — an SSRF vector. Nager.Date is a free public API; there is no operational reason to ever point
 * this elsewhere.
 *
 * <p>Network or non-2xx responses surface as {@link RuntimeException}s through the underlying
 * RestClient default error handler; {@link FederalHolidaySyncJob} catches per fetch.
 */
@Component
public class NagerDateClientImpl implements NagerDateClient {

  private static final String BASE_URL = "https://date.nager.at";

  private final RestClient http;

  public NagerDateClientImpl() {
    this.http = RestClient.builder().baseUrl(BASE_URL).build();
  }

  @Override
  public List<NagerHoliday> fetchPublicHolidays(int year, String countryCode) {
    return http.get()
        .uri("/api/v3/PublicHolidays/{year}/{country}", year, countryCode)
        .retrieve()
        .body(new org.springframework.core.ParameterizedTypeReference<List<NagerHoliday>>() {});
  }
}
