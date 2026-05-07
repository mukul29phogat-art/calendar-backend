package com.childcarewow.calendar.task;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface TaskInstanceOverrideRepository extends JpaRepository<TaskInstanceOverride, UUID> {

  Optional<TaskInstanceOverride> findByTaskIdAndOccurrenceDate(UUID taskId, LocalDate date);

  List<TaskInstanceOverride> findByTaskId(UUID taskId);

  @Modifying
  @Transactional
  void deleteByTaskId(UUID taskId);

  @Modifying
  @Transactional
  void deleteByTaskIdAndOccurrenceDateGreaterThanEqual(UUID taskId, LocalDate from);
}
