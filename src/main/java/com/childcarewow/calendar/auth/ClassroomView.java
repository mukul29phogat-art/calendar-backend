package com.childcarewow.calendar.auth;

import java.util.List;
import java.util.UUID;

/**
 * Classroom selector DTO. {@code staffUserIds} is sorted by user UUID for stable serialization;
 * empty list when no staff is assigned (a brand-new classroom or one whose teachers all left).
 */
public record ClassroomView(UUID id, UUID schoolId, String name, List<UUID> staffUserIds) {}
