package com.childcarewow.calendar.platform;

import com.childcarewow.calendar.exception.ValidationException;
import java.util.UUID;

/**
 * Validates that a platform-owned UUID (school, classroom, user, student) exists before the
 * calendar service writes a row referencing it. Cached implementation in {@link
 * PlatformEntityValidatorImpl}.
 */
public interface PlatformEntityValidator {

  boolean schoolExists(UUID schoolId);

  boolean classroomExists(UUID classroomId);

  boolean userExists(UUID userId);

  boolean studentExists(UUID studentId);

  boolean classroomBelongsToSchool(UUID classroomId, UUID schoolId);

  default void assertSchoolExists(UUID id) {
    if (!schoolExists(id)) {
      throw new ValidationException("schoolId", "School not found: " + id);
    }
  }

  default void assertClassroomExists(UUID id) {
    if (!classroomExists(id)) {
      throw new ValidationException("classroomId", "Classroom not found: " + id);
    }
  }

  default void assertUserExists(UUID id) {
    if (!userExists(id)) {
      throw new ValidationException("userId", "User not found: " + id);
    }
  }

  default void assertStudentExists(UUID id) {
    if (!studentExists(id)) {
      throw new ValidationException("studentId", "Student not found: " + id);
    }
  }

  default void assertClassroomBelongsToSchool(UUID classroomId, UUID schoolId) {
    if (!classroomBelongsToSchool(classroomId, schoolId)) {
      throw new ValidationException(
          "classroomId", "Classroom " + classroomId + " does not belong to school " + schoolId);
    }
  }
}
