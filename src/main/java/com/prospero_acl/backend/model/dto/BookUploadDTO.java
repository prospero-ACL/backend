package com.prospero_acl.backend.model.dto;

/**
 * A book's bytes lifted out of the multipart request. MultipartFile is only valid for the duration
 * of the request, and ingestion outlives it.
 */
public record BookUploadDTO(int position, String filename, byte[] content) {
}
