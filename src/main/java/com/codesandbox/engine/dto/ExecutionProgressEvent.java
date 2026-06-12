package com.codesandbox.engine.dto;

import com.codesandbox.engine.model.ExecutionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionProgressEvent {
    private String status; // e.g. "RECEIVED", "PULLING_IMAGE", "RUNNING", "COMPLETED", "ERROR"
    private String message;
    private ExecutionResult result;
}
