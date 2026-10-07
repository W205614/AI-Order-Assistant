package com.ai.assistant.dto;

import jakarta.validation.constraints.*;

public record ConfirmDraftDTO(
    @NotNull @Min(1) Long expectedVersion,
    @NotBlank @Size(max = 50) String recipientName,
    @NotBlank @Pattern(regexp = "[0-9+ -]{7,30}") String recipientPhone,
    @NotBlank @Size(max = 255) String deliveryAddress,
    @NotBlank @Size(max = 50) String deliveryRegion) {}
