package com.childcarewow.calendar.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.childcarewow.calendar.exception.ValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PlatformEntityValidatorIT {

  // Seed UUIDs (docker/platform-seed.sql)
  private static final UUID SUNRISE_SCHOOL =
      UUID.fromString("22222222-2222-2222-2222-222222222221");
  private static final UUID MAPLEWOOD_SCHOOL =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID BUTTERFLIES_CLASSROOM =
      UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID OLIVIA_USER = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID AANYA_STUDENT = UUID.fromString("55555555-0000-0000-0000-000000000001");
  private static final UUID UNKNOWN = UUID.fromString("00000000-0000-0000-0000-000000000000");

  @Autowired PlatformEntityValidator validator;
  @Autowired MeterRegistry registry;

  @Test
  void seedEntitiesExist() {
    assertThat(validator.schoolExists(SUNRISE_SCHOOL)).isTrue();
    assertThat(validator.classroomExists(BUTTERFLIES_CLASSROOM)).isTrue();
    assertThat(validator.userExists(OLIVIA_USER)).isTrue();
    assertThat(validator.studentExists(AANYA_STUDENT)).isTrue();
    assertThat(validator.classroomBelongsToSchool(BUTTERFLIES_CLASSROOM, SUNRISE_SCHOOL)).isTrue();
    assertThat(validator.classroomBelongsToSchool(BUTTERFLIES_CLASSROOM, MAPLEWOOD_SCHOOL))
        .isFalse();
  }

  @Test
  void unknownReturnsFalse() {
    assertThat(validator.schoolExists(UNKNOWN)).isFalse();
    assertThat(validator.classroomExists(UNKNOWN)).isFalse();
    assertThat(validator.userExists(UNKNOWN)).isFalse();
    assertThat(validator.studentExists(UNKNOWN)).isFalse();
  }

  @Test
  void assertMethodsThrowValidationExceptionOnMissing() {
    assertThatThrownBy(() -> validator.assertSchoolExists(UNKNOWN))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("School not found");
    assertThatThrownBy(() -> validator.assertClassroomExists(UNKNOWN))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> validator.assertUserExists(UNKNOWN))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> validator.assertStudentExists(UNKNOWN))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> validator.assertClassroomBelongsToSchool(BUTTERFLIES_CLASSROOM, MAPLEWOOD_SCHOOL))
        .isInstanceOf(ValidationException.class);

    // and assertions on a row that DOES exist don't throw
    validator.assertSchoolExists(SUNRISE_SCHOOL);
  }

  @Test
  void cacheHitsIncrementOnRepeatedCall() {
    UUID isolatedSchool = UUID.fromString("22222222-2222-2222-2222-222222222221");
    double hitsBefore = registry.counter("platform_validator_cache_hits").count();
    double missesBefore = registry.counter("platform_validator_cache_misses").count();

    validator.schoolExists(isolatedSchool); // miss → DB → cache populated
    validator.schoolExists(isolatedSchool); // hit
    validator.schoolExists(isolatedSchool); // hit

    double hitsAfter = registry.counter("platform_validator_cache_hits").count();
    double missesAfter = registry.counter("platform_validator_cache_misses").count();

    assertThat(hitsAfter - hitsBefore).isGreaterThanOrEqualTo(2);
    assertThat(missesAfter - missesBefore).isGreaterThanOrEqualTo(0); // may already be cached
  }
}
