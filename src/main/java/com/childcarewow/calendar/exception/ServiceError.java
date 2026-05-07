package com.childcarewow.calendar.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Inner error object inside {@link ServiceErrorResponse}. {@code field} omitted when null. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceError(String code, String message, String field) {}
