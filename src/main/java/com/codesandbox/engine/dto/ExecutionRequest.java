package com.codesandbox.engine.dto;

import lombok.Data;

@Data
public class ExecutionRequest {
    private String code;
    private String language;
}
