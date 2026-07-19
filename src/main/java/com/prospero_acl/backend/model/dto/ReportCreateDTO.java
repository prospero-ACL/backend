package com.prospero_acl.backend.model.dto;

import java.util.UUID;

public record ReportCreateDTO(
    String prompt,
    UUID[] chunks) {
}
