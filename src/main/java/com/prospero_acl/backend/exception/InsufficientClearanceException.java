package com.prospero_acl.backend.exception;

public class InsufficientClearanceException extends RuntimeException {
  public InsufficientClearanceException(String message) {
    super(message);
  }
}
