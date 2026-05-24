package com.minigenesys.telephony.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "phone_number_mappings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhoneNumberMapping {
    @Id
    @Column(nullable = false, unique = true)
    private String phoneNumber; // E.164 format, e.g. "+19783505660"

    @Column(nullable = false)
    private String tenantId; // e.g. "tenant-twilio"

    @Column(nullable = false)
    private String telephonyProvider; // e.g. "TWILIO", "FREESWITCH"

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
