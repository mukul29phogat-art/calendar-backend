package com.childcarewow.calendar.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for {@link EmailRenderer}. Each kind round-trips title + message; the
 * critical regression test is XSS-style HTML in user input — the renderer must escape it before
 * placing it in the body.
 */
class EmailRendererTest {

  private final EmailRenderer renderer = new EmailRenderer();

  @Test
  void eventInviteRendersTitleInSubjectAndBody() {
    Notification n = notification(NotificationKind.EVENT_INVITE, "Spring Concert", "Tonight 7pm");

    EmailContent content = renderer.render(n);

    assertThat(content.subject()).isEqualTo("You're invited: Spring Concert");
    assertThat(content.htmlBody()).contains("<h2>You're invited</h2>");
    assertThat(content.htmlBody()).contains("<strong>Spring Concert</strong>");
    assertThat(content.htmlBody()).contains("<p>Tonight 7pm</p>");
  }

  @Test
  void htmlInTitleIsEscapedInBodyButRawInSubject() {
    // Subject is plain text (no escape needed — email clients render it as text).
    // Body must escape because we're concatenating into HTML.
    Notification n =
        notification(
            NotificationKind.EVENT_UPDATED,
            "<script>alert('xss')</script>",
            "Don't <em>do</em> it");

    EmailContent content = renderer.render(n);

    // Body must NOT contain the raw script tag.
    assertThat(content.htmlBody()).doesNotContain("<script>alert");
    // Body MUST contain the escaped form. (HtmlUtils.htmlEscape uses entities like &lt; &gt;
    // &#39;.)
    assertThat(content.htmlBody()).contains("&lt;script&gt;");
    assertThat(content.htmlBody()).doesNotContain("<em>do</em>");
    // Subject is the raw title (email clients won't interpret it).
    assertThat(content.subject()).isEqualTo("Event updated: <script>alert('xss')</script>");
  }

  @Test
  void allEightKindsRenderWithoutNpe() {
    for (NotificationKind kind : NotificationKind.values()) {
      Notification n = notification(kind, "T", "M");
      EmailContent content = renderer.render(n);
      assertThat(content.subject()).isNotEmpty();
      assertThat(content.htmlBody()).contains("<!DOCTYPE html>");
      assertThat(content.htmlBody()).contains("</html>");
    }
  }

  @Test
  void nullTitleAndMessageFallToEmptyStrings() {
    Notification n = new Notification();
    n.setKind(NotificationKind.TASK_ASSIGNED);
    n.setMessage(null);
    n.setRelatedEntityTitle(null);

    EmailContent content = renderer.render(n);

    assertThat(content.subject()).isEqualTo("New task assigned: ");
    // Body is well-formed HTML even with empty user content.
    assertThat(content.htmlBody()).contains("<h2>You've been assigned a task</h2>");
    assertThat(content.htmlBody()).contains("<strong></strong>");
  }

  @Test
  void ampersandInTitleIsEscaped() {
    Notification n = notification(NotificationKind.TASK_UPDATED, "Salt & Pepper", "M");

    EmailContent content = renderer.render(n);

    // Body must use the entity-escaped form.
    assertThat(content.htmlBody()).contains("Salt &amp; Pepper");
    assertThat(content.htmlBody()).doesNotContain(">Salt & Pepper<");
  }

  @Test
  void wrapperSignatureLineAppears() {
    Notification n = notification(NotificationKind.TASK_OVERDUE, "Cleanup", "Due yesterday");
    EmailContent content = renderer.render(n);
    assertThat(content.htmlBody()).contains("<em>— ChildcareWow School Operations</em>");
  }

  private static Notification notification(NotificationKind kind, String title, String message) {
    Notification n = new Notification();
    n.setKind(kind);
    n.setRelatedEntityTitle(title);
    n.setMessage(message);
    return n;
  }
}
