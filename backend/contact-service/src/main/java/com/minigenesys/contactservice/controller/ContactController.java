package com.minigenesys.contactservice.controller;

import com.minigenesys.contactservice.dto.ContactResponse;
import com.minigenesys.contactservice.dto.CreateContactRequest;
import com.minigenesys.contactservice.service.ContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @PostMapping
    public ResponseEntity<ContactResponse> createContact(
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId,
            @Valid @RequestBody CreateContactRequest request) {
        ContactResponse response = contactService.createContact(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ContactResponse>> getAllContacts(
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId,
            @RequestParam(required = false) String search) {
        
        List<ContactResponse> contacts;
        if (search != null && !search.trim().isEmpty()) {
            contacts = contactService.searchContacts(tenantId, search);
        } else {
            contacts = contactService.getAllContacts(tenantId);
        }
        
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContactResponse> getContact(
            @PathVariable String id,
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId) {
        return ResponseEntity.ok(contactService.getContact(id, tenantId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ContactResponse> updateContact(
            @PathVariable String id,
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId,
            @Valid @RequestBody CreateContactRequest request) {
        return ResponseEntity.ok(contactService.updateContact(id, tenantId, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteContact(
            @PathVariable String id,
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId) {
        contactService.deleteContact(id, tenantId);
        return ResponseEntity.noContent().build();
    }
}
