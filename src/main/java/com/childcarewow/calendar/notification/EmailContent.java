package com.childcarewow.calendar.notification;

/**
 * Rendered email pair — the {@link EmailDispatcher#send} input shape. Subject is plain text; {@code
 * htmlBody} is fully HTML-escaped for the user-supplied portions (event title, etc.).
 */
public record EmailContent(String subject, String htmlBody) {}
