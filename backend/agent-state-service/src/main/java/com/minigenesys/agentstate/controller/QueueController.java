package com.minigenesys.agentstate.controller;

import com.minigenesys.agentstate.model.Queue;
import com.minigenesys.agentstate.repository.AgentRepository;
import com.minigenesys.agentstate.repository.QueueRepository;
import com.minigenesys.common.dto.QueueDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/queues")
@RequiredArgsConstructor
public class QueueController {

    private final QueueRepository queueRepository;
    private final AgentRepository agentRepository;

    @PostMapping
    @Transactional
    public ResponseEntity<QueueDto> createQueue(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody QueueDto dto) {

        Queue queue = Queue.builder()
                .tenantId(tenantId)
                .name(dto.getName())
                .agentIds(dto.getAgentIds() != null ? dto.getAgentIds() : new HashSet<>())
                .disableSkills(dto.isDisableSkills())
                .assignmentType(dto.getAssignmentType() != null ? dto.getAssignmentType() : "FIFO")
                .build();

        queue = queueRepository.save(queue);

        // Sync to agents
        String queueId = queue.getId();
        if (queue.getAgentIds() != null) {
            for (String agentId : queue.getAgentIds()) {
                agentRepository.findByIdAndTenantId(agentId, tenantId).ifPresent(agent -> {
                    if (agent.getQueueIds() == null) {
                        agent.setQueueIds(new HashSet<>());
                    }
                    agent.getQueueIds().add(queueId);
                    agentRepository.save(agent);
                });
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(mapToDto(queue));
    }

    @GetMapping
    public ResponseEntity<List<QueueDto>> getQueues(@RequestHeader("X-Tenant-Id") String tenantId) {
        List<Queue> queues = queueRepository.findByTenantId(tenantId);
        List<QueueDto> dtos = queues.stream().map(this::mapToDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<QueueDto> getQueue(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String id) {

        Queue queue = queueRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue not found"));
        return ResponseEntity.ok(mapToDto(queue));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<QueueDto> updateQueue(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String id,
            @RequestBody QueueDto dto) {

        Queue queue = queueRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue not found"));

        Set<String> oldAgentIds = queue.getAgentIds() != null ? new HashSet<>(queue.getAgentIds()) : new HashSet<>();
        Set<String> newAgentIds = dto.getAgentIds() != null ? new HashSet<>(dto.getAgentIds()) : new HashSet<>();

        queue.setName(dto.getName());
        queue.setAgentIds(newAgentIds);
        queue.setDisableSkills(dto.isDisableSkills());
        if (dto.getAssignmentType() != null) {
            queue.setAssignmentType(dto.getAssignmentType());
        }

        queue = queueRepository.save(queue);

        // Sync: Remove queueId from agents no longer in this queue
        for (String agentId : oldAgentIds) {
            if (!newAgentIds.contains(agentId)) {
                agentRepository.findByIdAndTenantId(agentId, tenantId).ifPresent(agent -> {
                    if (agent.getQueueIds() != null) {
                        agent.getQueueIds().remove(id);
                        agentRepository.save(agent);
                    }
                });
            }
        }

        // Sync: Add queueId to agents now in this queue
        for (String agentId : newAgentIds) {
            if (!oldAgentIds.contains(agentId)) {
                agentRepository.findByIdAndTenantId(agentId, tenantId).ifPresent(agent -> {
                    if (agent.getQueueIds() == null) {
                        agent.setQueueIds(new HashSet<>());
                    }
                    agent.getQueueIds().add(id);
                    agentRepository.save(agent);
                });
            }
        }

        return ResponseEntity.ok(mapToDto(queue));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> deleteQueue(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String id) {

        Queue queue = queueRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue not found"));

        if (queue.getAgentIds() != null) {
            for (String agentId : queue.getAgentIds()) {
                agentRepository.findByIdAndTenantId(agentId, tenantId).ifPresent(agent -> {
                    if (agent.getQueueIds() != null) {
                        agent.getQueueIds().remove(id);
                        agentRepository.save(agent);
                    }
                });
            }
        }

        queueRepository.delete(queue);
        return ResponseEntity.noContent().build();
    }

    private QueueDto mapToDto(Queue queue) {
        return QueueDto.builder()
                .id(queue.getId())
                .tenantId(queue.getTenantId())
                .name(queue.getName())
                .agentIds(queue.getAgentIds())
                .disableSkills(queue.isDisableSkills())
                .assignmentType(queue.getAssignmentType())
                .build();
    }
}
