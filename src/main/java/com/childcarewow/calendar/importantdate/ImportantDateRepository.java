package com.childcarewow.calendar.importantdate;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportantDateRepository extends JpaRepository<ImportantDate, UUID> {}
