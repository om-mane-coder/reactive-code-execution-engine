package com.codesandbox.engine.service;

import com.codesandbox.engine.model.ExecutionResult;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class DockerSandboxService {

    private final DockerClient dockerClient;

    public Mono<ExecutionResult> executeCode(String code, String language) {
        return Mono.fromCallable(() -> runCodeInContainer(code, language))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ExecutionResult runCodeInContainer(String code, String language) {
        String filename;
        String image;
        String[] cmd;

        if ("python".equalsIgnoreCase(language)) {
            filename = "script.py";
            image = "python:3.10-alpine";
            cmd = new String[]{"python", "/app/script.py"};
        } else if ("javascript".equalsIgnoreCase(language)) {
            filename = "script.js";
            image = "node:18-alpine";
            cmd = new String[]{"node", "/app/script.js"};
        } else {
            throw new IllegalArgumentException("Unsupported language: " + language);
        }

        // 1. Create a unique temp folder to avoid collision
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("sandbox-");
        } catch (IOException e) {
            log.error("Failed to create temp directory", e);
            return ExecutionResult.builder()
                    .stderr("Internal Server Error: Failed to setup execution sandbox")
                    .exitCode(-1)
                    .build();
        }

        File codeFile = new File(tempDir.toFile(), filename);
        try (FileWriter writer = new FileWriter(codeFile)) {
            writer.write(code);
        } catch (IOException e) {
            log.error("Failed to write user code to file", e);
            cleanup(tempDir.toFile());
            return ExecutionResult.builder()
                    .stderr("Internal Server Error: Failed to write code to sandbox")
                    .exitCode(-1)
                    .build();
        }

        String containerId = null;
        try {
            // 2. Ensure docker image is pulled
            try {
                dockerClient.pullImageCmd(image).start().awaitCompletion(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("Image pull interrupted, proceeding anyway", e);
            }

            // 3. Configure container (Volume Binds, RAM Limit: 128MB, CPU Limit: 0.5 cores)
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withBinds(new Bind(tempDir.toAbsolutePath().toString(), new Volume("/app")))
                    .withMemory(128 * 1024 * 1024L) // 128MB RAM limit
                    .withNanoCPUs(500_000_000L);    // 0.5 CPU limit

            CreateContainerResponse container = dockerClient.createContainerCmd(image)
                    .withHostConfig(hostConfig)
                    .withCmd(cmd)
                    .withNetworkDisabled(true) // Disable network to prevent outbound attacks
                    .exec();

            containerId = container.getId();
            long startTime = System.currentTimeMillis();

            // 4. Start container
            dockerClient.startContainerCmd(containerId).exec();

            // 5. Wait for completion with timeout
            WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
            dockerClient.waitContainerCmd(containerId).exec(waitCallback);

            int exitCode;
            boolean isTimeout = false;
            try {
                // Wait up to 5 seconds
                Integer status = waitCallback.awaitStatusCode(5, TimeUnit.SECONDS);
                exitCode = (status != null) ? status : -1;
            } catch (Exception e) {
                log.warn("Container timed out. Stopping and killing container {}", containerId);
                isTimeout = true;
                exitCode = -1;
                try {
                    dockerClient.stopContainerCmd(containerId).exec();
                } catch (Exception stopEx) {
                    log.error("Failed to stop container", stopEx);
                }
            }

            long executionTime = System.currentTimeMillis() - startTime;

            // 6. Gather logs
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            LogContainerResultCallback logCallback = new LogContainerResultCallback() {
                @Override
                public void onNext(com.github.dockerjava.api.model.Frame item) {
                    if (item.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                        stdout.append(new String(item.getPayload()));
                    } else if (item.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR) {
                        stderr.append(new String(item.getPayload()));
                    }
                    super.onNext(item);
                }
            };

            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .exec(logCallback)
                    .awaitCompletion(2, TimeUnit.SECONDS);

            return ExecutionResult.builder()
                    .stdout(stdout.toString().trim())
                    .stderr(stderr.toString().trim())
                    .exitCode(exitCode)
                    .isTimeout(isTimeout)
                    .executionTimeMs(executionTime)
                    .build();

        } catch (Exception e) {
            log.error("Container execution failed", e);
            return ExecutionResult.builder()
                    .stderr("Execution Error: " + e.getMessage())
                    .exitCode(-1)
                    .build();
        } finally {
            // 7. Cleanup
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception e) {
                    log.warn("Failed to clean up container {}", containerId, e);
                }
            }
            cleanup(tempDir.toFile());
        }
    }

    private void cleanup(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    cleanup(child);
                }
            }
        }
        file.delete();
    }
}
