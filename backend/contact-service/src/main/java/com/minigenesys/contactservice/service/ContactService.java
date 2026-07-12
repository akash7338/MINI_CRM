package com.minigenesys.contactservice.service;

import com.minigenesys.contactservice.dto.ContactResponse;
import com.minigenesys.contactservice.dto.CreateContactRequest;
import com.minigenesys.contactservice.model.Contact;
import com.minigenesys.contactservice.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private final ContactRepository contactRepository;

    @Transactional
    public ContactResponse createContact(String tenantId, CreateContactRequest request) {
        Contact contact = Contact.builder()
                .tenantId(tenantId)
                .name(request.getName())
                .phoneNumber(request.getPhoneNumber())
                .email(request.getEmail())
                .metadata(request.getMetadata())
                .build();

        contact = contactRepository.save(contact);
        log.info("Created contact: id={}, tenantId={}, name={}", contact.getId(), tenantId, contact.getName());
        
        return mapToResponse(contact);
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> getAllContacts(String tenantId) {
        return contactRepository.findByTenantId(tenantId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ContactResponse getContact(String id, String tenantId) {
        Contact contact = contactRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));

        if (!contact.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return mapToResponse(contact);
    }

    @Transactional
    public ContactResponse updateContact(String id, String tenantId, CreateContactRequest request) {
        Contact contact = contactRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));

        if (!contact.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        contact.setName(request.getName());
        contact.setPhoneNumber(request.getPhoneNumber());
        contact.setEmail(request.getEmail());
        contact.setMetadata(request.getMetadata());
        
        contact = contactRepository.save(contact);
        log.info("Updated contact: id={}", id);
        
        return mapToResponse(contact);
    }

    @Transactional
    public void deleteContact(String id, String tenantId) {
        Contact contact = contactRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));

        if (!contact.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        contactRepository.delete(contact);
        log.info("Deleted contact: id={}", id);
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> searchContacts(String tenantId, String query) {
        List<Contact> contacts = contactRepository.findByTenantIdAndNameContainingIgnoreCase(tenantId, query);
        
        if (contacts.isEmpty()) {
            contacts = contactRepository.findByTenantIdAndPhoneNumberContaining(tenantId, query);
        }
        
        return contacts.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private ContactResponse mapToResponse(Contact contact) {
        return ContactResponse.builder()
                .id(contact.getId())
                .tenantId(contact.getTenantId())
                .name(contact.getName())
                .phoneNumber(contact.getPhoneNumber())
                .email(contact.getEmail())
                .metadata(contact.getMetadata())
                .createdAt(contact.getCreatedAt())
                .updatedAt(contact.getUpdatedAt())
                .build();
    }
}
