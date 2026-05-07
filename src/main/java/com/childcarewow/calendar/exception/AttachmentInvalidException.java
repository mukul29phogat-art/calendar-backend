package com.childcarewow.calendar.exception;

import org.springframework.http.HttpStatus;

public class AttachmentInvalidException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public AttachmentInvalidException(String reason) {
    super("ATTACHMENT_INVALID", HttpStatus.BAD_REQUEST, "attachment", reason);
  }
}
