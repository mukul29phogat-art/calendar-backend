package com.childcarewow.calendar.task;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/v1/tasks/{id}/status}. Minimal wire shape so the Kanban
 * drag-and-drop doesn't need to round-trip the entire task body for a one-field change.
 *
 * <p>The same mutation could be expressed via {@code PUT} (Part 8.4), but a focused PATCH gives the
 * FE a smaller payload, faster optimistic-update path, and a discoverable endpoint per
 * status-change use case. {@code TaskStatus} is the enum from Part 1.2 — invalid values fail
 * Jackson deserialization with a 400 before the controller body runs.
 */
public record UpdateTaskStatusRequest(@NotNull TaskStatus status) {}
