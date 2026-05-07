package com.childcarewow.calendar.attachment;

import com.childcarewow.calendar.audit.Audited;
import com.childcarewow.calendar.auth.UserPrincipal;
import com.childcarewow.calendar.policy.PolicyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hands out signed upload URLs for event/task attachments. The actual byte transfer goes directly
 * from the FE to Supabase Storage; the backend never streams the bytes.
 *
 * <p>Audited via {@link Audited} so every signed-URL hand-out lands in {@code audit_events}; the
 * row is keyed by {@code attachmentName} via {@code idFrom = "attachmentName"}.
 */
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

  private final FileUploadService service;
  private final PolicyService policy;

  public AttachmentController(FileUploadService service, PolicyService policy) {
    this.service = service;
    this.policy = policy;
  }

  @PostMapping("/sign-upload")
  @Audited(action = "ATTACHMENT_SIGN", targetType = "ATTACHMENT", idFrom = "attachmentName")
  public ResponseEntity<SignedUploadResult> signUpload(
      @AuthenticationPrincipal UserPrincipal actor, @Valid @RequestBody SignUploadRequest req) {
    // Same gate as event.create — anyone allowed to create events/tasks (i.e. non-PARENT) can
    // attach files. Parents are excluded uniformly with the rest of the create surface.
    policy.assertCan(actor, "event.create");
    SignedUploadResult result =
        service.signUpload(req.schoolId(), req.filename(), req.mimeType(), req.sizeBytes());
    return ResponseEntity.ok(result);
  }
}
