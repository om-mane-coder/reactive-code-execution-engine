package com.codesandbox.engine.dto;

import com.codesandbox.engine.model.ExecutionResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueuedExecutionResult implements Serializable {
    private String submissionId;
    private ExecutionResult result;
}
