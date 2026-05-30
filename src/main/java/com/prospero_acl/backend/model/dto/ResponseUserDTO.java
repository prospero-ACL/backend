package com.prospero_acl.backend.model.dto;

import jakarta.annotation.Nullable;

public record ResponseUserDTO(
    String id,
    String email,
    String theme,
    @Nullable String name,
    @Nullable String avatarUrl) {
}
