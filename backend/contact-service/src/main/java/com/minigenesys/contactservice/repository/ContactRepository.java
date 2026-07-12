package com.minigenesys.contactservice.repository;

import com.minigenesys.contactservice.model.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContactRepository extends JpaRepository<Contact, String> {
    List<Contact> findByTenantId(String tenantId);
    List<Contact> findByTenantIdAndNameContainingIgnoreCase(String tenantId, String name);
    List<Contact> findByTenantIdAndPhoneNumberContaining(String tenantId, String phoneNumber);
}
