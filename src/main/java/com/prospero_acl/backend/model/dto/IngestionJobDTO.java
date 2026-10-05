package com.prospero_acl.backend.model.dto;

import java.util.UUID;

import com.prospero_acl.backend.model.enums.IngestionStatus;

public record IngestionJobDTO(
    UUID id,
    String trilogy,
    IngestionStatus status,
    int booksDone,
    int booksTotal,
    int chunksWritten,
    String error) {
}
