package com.prospero_acl.backend.model.dto;

import java.util.List;
import java.util.UUID;

public record ConversationResponseDTO(
    UUID id,
    List<ConversationTurnDTO> turns) {

}
