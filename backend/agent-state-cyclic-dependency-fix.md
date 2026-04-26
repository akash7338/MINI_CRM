# Agent State Service: Cyclic Dependency Fix

### 1. Cause
The circular dependency was caused by `AgentStateService` requiring `KafkaMessaging` to publish events, while `KafkaMessaging` required `AgentStateService` to process consumed routing events.

### 2. Old Implementation (Cycle)
```java
// AgentStateService
public class AgentStateService {
    private final KafkaMessaging kafkaMessaging; // Dependency 1
    // ... logic calling kafkaMessaging
}

// KafkaMessaging
public class KafkaMessaging {
    private final AgentStateService agentStateService; // Dependency 2 (CYCLE)
    @KafkaListener(topics = "routing-events")
    public void consume(String msg) { agentStateService.handle(msg); }
}
```

### 3. New Implementation (Decoupled)
```java
// 1. Specialized Producer (No Dependencies)
public class AgentEventProducer {
    public void publish(AgentEvent event) { /* kafkaTemplate.send */ }
}

// 2. Service (Depends only on Producer)
public class AgentStateService {
    private final AgentEventProducer agentEventProducer;
}

// 3. Specialized Consumer (Depends on Service)
public class RoutingEventConsumer {
    private final AgentStateService agentStateService;
    @KafkaListener(topics = "routing-events")
    public void consume(String msg) { agentStateService.handle(msg); }
}
```

### 4. Why it works
The refactor transforms a bi-directional dependency cycle into a linear directed acyclic graph (DAG): Consumer → Service → Producer.
