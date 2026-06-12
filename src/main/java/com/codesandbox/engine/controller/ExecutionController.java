package com.codesandbox.engine.controller;

import com.codesandbox.engine.dto.ExecutionRequest;
import com.codesandbox.engine.model.ExecutionResult;
import com.codesandbox.engine.service.DockerSandboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ExecutionController {

    private final DockerSandboxService sandboxService;

    @PostMapping("/execute")
    public Mono<ResponseEntity<ExecutionResult>> executeCode(@RequestBody ExecutionRequest request) {
        if (request.getCode() == null || request.getCode().trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(ExecutionResult.builder().stderr("Code cannot be empty").exitCode(-1).build()));
        }
        if (request.getLanguage() == null || request.getLanguage().trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(ExecutionResult.builder().stderr("Language must be specified").exitCode(-1).build()));
        }

        return sandboxService.executeCode(request.getCode(), request.getLanguage())
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.status(500).build());
    }
}
