package com.minigenesys.callservice.controller;

import com.minigenesys.callservice.model.Campaign;
import com.minigenesys.callservice.repository.CampaignRepository;
import com.minigenesys.common.dto.CampaignDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignRepository campaignRepository;

    @PostMapping
    public ResponseEntity<CampaignDto> createCampaign(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody CampaignDto dto) {
        
        Campaign campaign = Campaign.builder()
                .tenantId(tenantId)
                .name(dto.getName())
                .dids(dto.getDids())
                .queueId(dto.getQueueId())
                .holdPrompt(dto.getHoldPrompt())
                .recordingPercentage(dto.getRecordingPercentage())
                .callerId(dto.getCallerId())
                .build();
        
        campaign = campaignRepository.save(campaign);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToDto(campaign));
    }

    @GetMapping
    public ResponseEntity<List<CampaignDto>> getCampaigns(@RequestHeader("X-Tenant-Id") String tenantId) {
        List<Campaign> campaigns = campaignRepository.findByTenantId(tenantId);
        List<CampaignDto> dtos = campaigns.stream().map(this::mapToDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CampaignDto> getCampaign(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String id) {
        
        Campaign campaign = campaignRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        return ResponseEntity.ok(mapToDto(campaign));
    }

    @GetMapping("/by-did/{did}")
    public ResponseEntity<CampaignDto> getCampaignByDid(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String did) {
        
        Campaign campaign = campaignRepository.findByDidAndTenantId(did, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found for DID: " + did));
        return ResponseEntity.ok(mapToDto(campaign));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CampaignDto> updateCampaign(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String id,
            @RequestBody CampaignDto dto) {
        
        Campaign campaign = campaignRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        
        campaign.setName(dto.getName());
        campaign.setDids(dto.getDids());
        campaign.setQueueId(dto.getQueueId());
        campaign.setHoldPrompt(dto.getHoldPrompt());
        campaign.setRecordingPercentage(dto.getRecordingPercentage());
        campaign.setCallerId(dto.getCallerId());
        
        campaign = campaignRepository.save(campaign);
        return ResponseEntity.ok(mapToDto(campaign));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCampaign(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String id) {
        
        Campaign campaign = campaignRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        
        campaignRepository.delete(campaign);
        return ResponseEntity.noContent().build();
    }

    private CampaignDto mapToDto(Campaign campaign) {
        return CampaignDto.builder()
                .id(campaign.getId())
                .tenantId(campaign.getTenantId())
                .name(campaign.getName())
                .dids(campaign.getDids())
                .queueId(campaign.getQueueId())
                .holdPrompt(campaign.getHoldPrompt())
                .recordingPercentage(campaign.getRecordingPercentage())
                .callerId(campaign.getCallerId())
                .build();
    }
}
