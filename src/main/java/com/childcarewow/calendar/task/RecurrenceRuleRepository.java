package com.childcarewow.calendar.task;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurrenceRuleRepository extends JpaRepository<RecurrenceRule, UUID> {}
