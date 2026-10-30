package com.viratech.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NotificationRequest(
        @NotBlank
        @Email
        @Size(max = 255)
        String consumerEmail,

        @NotBlank
        @Size(max = 255)
        String content
) {
}
