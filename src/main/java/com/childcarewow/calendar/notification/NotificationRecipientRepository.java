package com.childcarewow.calendar.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRecipientRepository
    extends JpaRepository<NotificationRecipient, NotificationRecipientId> {}
