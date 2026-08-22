package com.prospero_acl.backend.exception;

public class EmptyDocumentException extends RuntimeException {
  public EmptyDocumentException(String message) {
    super(message);
  }
}
