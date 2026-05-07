package com.childcarewow.calendar.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Student selector DTO. {@code dob} is optional (the platform allows null DOB during the consent
 * stage; not all rows have it set yet); serialized as ISO-8601 {@code yyyy-MM-dd}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record StudentView(UUID id, UUID schoolId, UUID classroomId, String name, LocalDate dob) {}
