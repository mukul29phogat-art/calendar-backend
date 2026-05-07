package com.childcarewow.calendar.task;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskInstanceOverrideRepository extends JpaRepository<TaskInstanceOverride, UUID> {}
