package com.minigenesys.userservice.repository;

import com.minigenesys.userservice.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {
    // Primary lookup is by id (tenantId string), handled by JpaRepository.findById()
}
