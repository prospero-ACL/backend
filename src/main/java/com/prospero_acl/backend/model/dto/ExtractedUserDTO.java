package com.prospero_acl.backend.model.dto;

import jakarta.annotation.Nullable;

public record ExtractedUserDTO(
    String providerId,
    String email,
    String name,
    @Nullable String avatarUrl) {
}
