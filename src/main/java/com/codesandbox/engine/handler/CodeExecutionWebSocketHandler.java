package com.codesandbox.engine.handler;

import com.codesandbox.engine.config.RabbitMQConfig;
import com.codesandbox.engine.dto.ExecutionProgressEvent;
import com.codesandbox.engine.dto.ExecutionRequest;
import com.codesandbox.engine.dto.QueuedExecutionRequest;
import com.codesandbox.engine.registry.ExecutionRegistry;
import com.codesandbox.engine.service.DockerSandboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@Slf4j
public class CodeExecutionWebSocketHandler implements WebSocketHandler {

    private final ObjectProvider<AmqpTemplate> amqpTemplateProvider;
    private final ExecutionRegistry executionRegistry;
    private final DockerSandboxService sandboxService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.use-queue:false}")
    private boolean useQueue;

    public CodeExecutionWebSocketHandler(
            ObjectProvider<AmqpTemplate> amqpTemplateProvider,
            ExecutionRegistry executionRegistry,
            DockerSandboxService sandboxService) {
        this.amqpTemplateProvider = amqpTemplateProvider;
        this.executionRegistry = executionRegistry;
        this.sandboxService = sandboxService;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        return session.send(
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .flatMap(payload -> {
                            try {
                                ExecutionRequest request = objectMapper.readValue(payload, ExecutionRequest.class);
                                String submissionId = UUID.randomUUID().toString();

                                AmqpTemplate amqpTemplate = amqpTemplateProvider.getIfAvailable();

                                if (useQueue && amqpTemplate != null) {
                                    QueuedExecutionRequest queuedRequest = new QueuedExecutionRequest(
                                            submissionId,
                                            request.getCode(),
                                            request.getLanguage()
                                    );

                                    Mono<com.codesandbox.engine.model.ExecutionResult> resultMono =
                                            executionRegistry.register(submissionId).asMono();

                                    return Flux.concat(
                                            emitEvent(session, "RECEIVED", "Request received, mapping submission ID: " + submissionId),
                                            emitEvent(session, "QUEUED", "Queueing request to execution broker..."),
                                            Mono.fromRunnable(() -> {
                                                log.info("Publishing task to queue for submissionId: {}", submissionId);
                                                amqpTemplate.convertAndSend(RabbitMQConfig.QUEUE_NAME, queuedRequest);
                                            }).then(emitEvent(session, "EXECUTING", "Worker is executing in isolated sandbox...")),
                                            resultMono.flatMapMany(result -> emitFinalEvent(session, result))
                                                    .doFinally(signalType -> executionRegistry.remove(submissionId))
                                    );
                                } else {
                                    // Direct Local Sandbox Execution Mode (Fallback when RabbitMQ is disabled/unavailable)
                                    String modeMsg = (amqpTemplate == null) 
                                            ? "Request received (Direct fallback: RabbitMQ is offline)"
                                            : "Request received (Direct Mode)";
                                    return Flux.concat(
                                            emitEvent(session, "RECEIVED", modeMsg),
                                            emitEvent(session, "QUEUED", "Bypassing queue (Direct Execution)..."),
                                            emitEvent(session, "EXECUTING", "Worker is executing in isolated sandbox..."),
                                            sandboxService.executeCode(request.getCode(), request.getLanguage())
                                                    .flatMapMany(result -> emitFinalEvent(session, result))
                                    );
                                }
                            } catch (JsonProcessingException e) {
                                log.error("Failed to parse WebSocket execution request", e);
                                return emitEvent(session, "ERROR", "Invalid payload format. Expected {code: ..., language: ...}");
                            }
                        })
        );
    }

    private Mono<WebSocketMessage> emitEvent(WebSocketSession session, String status, String message) {
        try {
            ExecutionProgressEvent event = ExecutionProgressEvent.builder()
                    .status(status)
                    .message(message)
                    .build();
            String json = objectMapper.writeValueAsString(event);
            return Mono.just(session.textMessage(json));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    private Flux<WebSocketMessage> emitFinalEvent(WebSocketSession session, com.codesandbox.engine.model.ExecutionResult result) {
        try {
            ExecutionProgressEvent event = ExecutionProgressEvent.builder()
                    .status("COMPLETED")
                    .message("Code execution finished.")
                    .result(result)
                    .build();
            String json = objectMapper.writeValueAsString(event);
            return Flux.just(session.textMessage(json));
        } catch (JsonProcessingException e) {
            return Flux.error(e);
        }
    }
}
