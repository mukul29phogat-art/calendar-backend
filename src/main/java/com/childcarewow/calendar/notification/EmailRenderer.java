package com.childcarewow.calendar.notification;

import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * Per-kind email subject + HTML body renderer (Part 11.5). Output feeds {@link
 * EmailDispatcher#send}.
 *
 * <p><b>Templating choice.</b> Playbook offered Thymeleaf or {@code String.format}; with 8
 * notification kinds and short bodies, plain {@code String.format} is enough. HTML-escape via
 * {@link HtmlUtils#htmlEscape} on every user-supplied substitution (the {@code message} and {@code
 * relatedEntityTitle} columns hold operator + parent input — they can contain {@code <}, {@code >},
 * {@code &}). The fixed wrapper HTML is hand-authored so escaping it would be wrong.
 *
 * <p><b>Structure.</b> Each kind gets a static-strings subject + body pair, with the entity title
 * and message HTML-escaped at substitution time. Subject is plain text (no escape needed). Body is
 * `&lt;p&gt;`-wrapped HTML so the FE's bell-rendered preview and an HTML-capable email client both
 * look right.
 */
@Service
public class EmailRenderer {

  /**
   * Render the email pair for the given notification. The notification's {@code relatedEntityTitle}
   * fills the headline; {@code message} fills the body. Both are HTML-escaped.
   */
  public EmailContent render(Notification notification) {
    String title = nullSafe(notification.getRelatedEntityTitle());
    String escapedTitle = HtmlUtils.htmlEscape(title);
    String escapedMessage = HtmlUtils.htmlEscape(nullSafe(notification.getMessage()));

    return switch (notification.getKind()) {
      case EVENT_INVITE ->
          new EmailContent(
              "You're invited: " + title,
              wrap(
                  "<h2>You're invited</h2>",
                  "<p><strong>" + escapedTitle + "</strong></p>",
                  "<p>" + escapedMessage + "</p>"));
      case EVENT_UPDATED ->
          new EmailContent(
              "Event updated: " + title,
              wrap(
                  "<h2>Event updated</h2>",
                  "<p><strong>" + escapedTitle + "</strong></p>",
                  "<p>" + escapedMessage + "</p>"));
      case EVENT_CANCELLED ->
          new EmailContent(
              "Event cancelled: " + title,
              wrap(
                  "<h2>Event cancelled</h2>",
                  "<p><strong>" + escapedTitle + "</strong></p>",
                  "<p>" + escapedMessage + "</p>"));
      case TASK_ASSIGNED ->
          new EmailContent(
              "New task assigned: " + title,
              wrap(
                  "<h2>You've been assigned a task</h2>",
                  "<p><strong>" + escapedTitle + "</strong></p>",
                  "<p>" + escapedMessage + "</p>"));
      case TASK_UPDATED ->
          new EmailContent(
              "Task updated: " + title,
              wrap(
                  "<h2>Task updated</h2>",
                  "<p><strong>" + escapedTitle + "</strong></p>",
                  "<p>" + escapedMessage + "</p>"));
      case TASK_DELETED ->
          new EmailContent(
              "Task removed: " + title,
              wrap(
                  "<h2>Task removed</h2>",
                  "<p><strong>" + escapedTitle + "</strong></p>",
                  "<p>" + escapedMessage + "</p>"));
      case TASK_STATUS_CHANGED ->
          new EmailContent(
              "Task marked done: " + title,
              wrap(
                  "<h2>Task complete</h2>",
                  "<p><strong>" + escapedTitle + "</strong></p>",
                  "<p>" + escapedMessage + "</p>"));
      case TASK_OVERDUE ->
          new EmailContent(
              "Task overdue: " + title,
              wrap(
                  "<h2>Task overdue</h2>",
                  "<p><strong>" + escapedTitle + "</strong></p>",
                  "<p>" + escapedMessage + "</p>"));
    };
  }

  private static String wrap(String... bodyParts) {
    StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><body>");
    for (String part : bodyParts) {
      sb.append(part);
    }
    sb.append("<p><em>— ChildcareWow School Operations</em></p></body></html>");
    return sb.toString();
  }

  private static String nullSafe(String s) {
    return s == null ? "" : s;
  }
}
