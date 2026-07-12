package com.minigenesys.contactservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactResponse {
    private String id;
    private String tenantId;
    private String name;
    private String phoneNumber;
    private String email;
    private String metadata;
    private Instant createdAt;
    private Instant updatedAt;
}
