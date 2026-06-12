package com.codesandbox.engine.registry;

import com.codesandbox.engine.model.ExecutionResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionRegistry {

    private final Map<String, Sinks.One<ExecutionResult>> registry = new ConcurrentHashMap<>();

    public Sinks.One<ExecutionResult> register(String submissionId) {
        Sinks.One<ExecutionResult> sink = Sinks.one();
        registry.put(submissionId, sink);
        return sink;
    }

    public void complete(String submissionId, ExecutionResult result) {
        Sinks.One<ExecutionResult> sink = registry.remove(submissionId);
        if (sink != null) {
            sink.tryEmitValue(result);
        }
    }

    public void remove(String submissionId) {
        registry.remove(submissionId);
    }
}
