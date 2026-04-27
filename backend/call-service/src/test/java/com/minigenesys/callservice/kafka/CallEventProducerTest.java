package com.minigenesys.callservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.callservice.dto.CallEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CallEventProducerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CallEventProducer callEventProducer;

    @Test
    public void testPublishCallEventFails_ThrowsRuntimeException() throws Exception {
        CallEvent event = CallEvent.builder()
                .callId("call-1")
                .tenantId("tenant-1")
                .build();

        String message = "{\"callId\":\"call-1\"}";
        when(objectMapper.writeValueAsString(event)).thenReturn(message);

        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka down"));
        
        when(kafkaTemplate.send(anyString(), eq("tenant-1"), eq(message))).thenReturn(future);

        assertThrows(RuntimeException.class, () -> {
            callEventProducer.publishCallEvent(event);
        });
    }
}
