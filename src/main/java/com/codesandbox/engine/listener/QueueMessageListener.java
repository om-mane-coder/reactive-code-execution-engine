package com.codesandbox.engine.listener;

import com.codesandbox.engine.config.RabbitMQConfig;
import com.codesandbox.engine.dto.QueuedExecutionRequest;
import com.codesandbox.engine.registry.ExecutionRegistry;
import com.codesandbox.engine.service.DockerSandboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueMessageListener {

    private final DockerSandboxService sandboxService;
    private final ExecutionRegistry executionRegistry;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void processExecutionRequest(QueuedExecutionRequest request) {
        log.info("Received queued execution request for submissionId: {}", request.getSubmissionId());

        sandboxService.executeCode(request.getCode(), request.getLanguage())
                .subscribe(
                        result -> {
                            log.info("Finished execution for submissionId: {}", request.getSubmissionId());
                            executionRegistry.complete(request.getSubmissionId(), result);
                        },
                        error -> {
                            log.error("Execution failed for submissionId: {}", request.getSubmissionId(), error);
                            executionRegistry.complete(request.getSubmissionId(),
                                    com.codesandbox.engine.model.ExecutionResult.builder()
                                            .stderr("Queue execution failed: " + error.getMessage())
                                            .exitCode(-1)
                                            .build());
                        }
                );
    }
}
