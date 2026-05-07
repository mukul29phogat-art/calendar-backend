package com.childcarewow.calendar.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationReadRepository
    extends JpaRepository<NotificationRead, NotificationReadId> {}
