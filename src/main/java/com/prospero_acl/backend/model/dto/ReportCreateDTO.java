package com.prospero_acl.backend.model.dto;

import java.util.UUID;

import com.prospero_acl.backend.model.enums.DocumentScope;

public record ReportCreateDTO(
    String prompt,
    DocumentScope scope,
    UUID[] chunks) {
}
