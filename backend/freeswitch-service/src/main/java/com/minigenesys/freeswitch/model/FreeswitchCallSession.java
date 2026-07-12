package com.minigenesys.freeswitch.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "freeswitch_call_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreeswitchCallSession {
    @Id
    private String customerUuid; // FreeSWITCH Channel UUID for the customer call leg
    private String internalCallId; // call-service Call ID
    private String tenantId;
    private String callerId;
    private String toNumber; // Destination number for outbound calls
    private String direction; // "INBOUND" | "OUTBOUND"
    private String assignedAgentId;
    private String agentUuid; // FreeSWITCH Channel UUID for the agent call leg
    private String status; // PARKED, DIALING_AGENT, BRIDGED, COMPLETED
}
