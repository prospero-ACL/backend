package com.prospero_acl.backend.model.dto;

public record RequestDocumentUploadDTO(
    String name,
    String text,
    String userId) {
}
