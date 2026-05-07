package com.childcarewow.calendar.auth;

import java.util.UUID;

/**
 * School-switcher DTO. {@code timezone} is a {@link String} (IANA name like {@code
 * America/New_York}), not a {@link java.time.ZoneId}, because Jackson serializes {@code ZoneId} as
 * a JSON object — playbook common-failure-points warns against it. The FE treats this as an opaque
 * string and never reasons about timezones client-side.
 */
public record SchoolView(UUID id, String name, String timezone) {}
