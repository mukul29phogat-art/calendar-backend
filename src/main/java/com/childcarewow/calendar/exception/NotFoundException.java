package com.childcarewow.calendar.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class NotFoundException extends ServiceException {

  private static final long serialVersionUID = 1L;

  public NotFoundException(String entity, UUID id) {
    super("NOT_FOUND", HttpStatus.NOT_FOUND, null, entity + " not found: " + id);
  }
}
