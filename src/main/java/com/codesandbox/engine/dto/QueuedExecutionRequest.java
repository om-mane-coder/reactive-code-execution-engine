package com.codesandbox.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueuedExecutionRequest implements Serializable {
    private String submissionId;
    private String code;
    private String language;
}
