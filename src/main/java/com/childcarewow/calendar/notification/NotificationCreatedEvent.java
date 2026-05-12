package com.childcarewow.calendar.notification;

import java.util.UUID;

/**
 * Spring application event published by {@link NotificationService} immediately after a {@code
 * notifications} row + its recipient rows are saved. The {@link NotificationDispatchListener}
 * subscribes with {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so dispatch only runs
 * when the user-facing transaction actually committed — if the create rolls back, no email goes
 * out.
 *
 * <p>Payload is intentionally just the id — the listener re-loads the row to see committed data
 * (the in-memory entity could have references to rolled-back state via JPA's first-level cache).
 */
public record NotificationCreatedEvent(UUID notificationId) {}
