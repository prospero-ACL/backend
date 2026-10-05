package com.prospero_acl.backend.model.dto;

public record ConversationTurnDTO(
    Integer position,
    String prompt,
    String reply) {
}
