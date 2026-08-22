package com.prospero_acl.backend.exception;

public class UnreadablePdfException extends RuntimeException {
  public UnreadablePdfException(String message, Throwable cause) {
    super(message, cause);
  }
}
