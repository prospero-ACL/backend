package com.prospero_acl.backend.model.dto;

import java.util.List;
import java.util.UUID;

import com.prospero_acl.backend.model.enums.ReportStatus;

public record ReportResponseDTO(
    UUID id,
    ReportStatus status,
    List<ReportTurnDTO> turns) {

}
