package com.glr.deenwallet.monitoring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ErrorReportRequest {

    private String errorType;
    private String message;
    private String stack;
    private Integer statusCode;
    private String url;
    private Integer line;
    private Integer col;
    private String userAgent;
    private List<String> actionBuffer;
    private Map<String, Object> extra;
}
