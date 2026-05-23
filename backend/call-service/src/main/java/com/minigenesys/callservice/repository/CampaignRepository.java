package com.minigenesys.callservice.repository;

import com.minigenesys.callservice.model.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, String> {
    List<Campaign> findByTenantId(String tenantId);
    Optional<Campaign> findByIdAndTenantId(String id, String tenantId);

    @Query("SELECT c FROM Campaign c JOIN c.dids d WHERE d = :did AND c.tenantId = :tenantId")
    Optional<Campaign> findByDidAndTenantId(@Param("did") String did, @Param("tenantId") String tenantId);
}
