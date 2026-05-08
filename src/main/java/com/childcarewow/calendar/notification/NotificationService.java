package com.childcarewow.calendar.notification;

import com.childcarewow.calendar.event.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Notification dispatcher stub. The real implementation lands in Part 5.8 of the playbook
 * ("Notification dispatcher: EVENT_INVITE write"). This stub exists so {@link
 * com.childcarewow.calendar.event.EventService} can call it from Part 5.1 onwards without a
 * compile-time dep on Series-5.8 work.
 *
 * <p>Once 5.8 lands, replace these no-ops with the real flow: write {@code notifications} + {@code
 * notification_recipients} rows, respect the holiday-pause hook from {@code
 * timezoneService.isHolidayForSchool}, and queue PUSH delivery via FCM.
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  /** No-op until Part 5.8. Logged at DEBUG so test output stays quiet. */
  public void dispatchEventCreated(Event event) {
    if (event == null) {
      return;
    }
    log.debug(
        "[stub] dispatchEventCreated event={} school={} title={}",
        event.getId(),
        event.getSchoolId(),
        event.getTitle());
  }

  /**
   * No-op until Part 5.8. The real impl will diff {@code prev} → {@code next} and emit
   * UPDATE/CANCEL/INVITE notifications depending on whether {@code inviteParents} flipped or core
   * fields (date/time/classroom) changed.
   */
  public void dispatchEventUpdated(Event prev, Event next) {
    if (next == null) {
      return;
    }
    log.debug(
        "[stub] dispatchEventUpdated event={} title={} -> {}",
        next.getId(),
        prev == null ? null : prev.getTitle(),
        next.getTitle());
  }
}
