package com.childcarewow.calendar.federalholiday;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/**
 * Minimal projection of Nager.Date's {@code /api/v3/PublicHolidays/{year}/{country}} response. The
 * upstream payload carries {@code name}, {@code countryCode}, {@code fixed}, {@code global}, {@code
 * counties}, {@code launchYear}, {@code types} — we ignore them. For US holidays {@code localName}
 * and {@code name} match; we use {@code localName} to be locale-friendly if we ever extend beyond
 * US.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NagerHoliday(LocalDate date, String localName) {}
