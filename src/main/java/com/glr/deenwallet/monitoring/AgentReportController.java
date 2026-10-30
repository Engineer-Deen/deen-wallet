package com.glr.deenwallet.monitoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/** Receives the once-a-minute report from an extra machine. Open to the internet, so the secret token is checked first. */
@RestController
@RequestMapping("/api/monitor/agent")
public class AgentReportController {

    private static final int MAX_BYTES = 200_000;

    private final AgentReportService service;
    private final SecurityEventService events;
    private final ObjectMapper mapper;

    public AgentReportController(AgentReportService service, SecurityEventService events, ObjectMapper mapper) {
        this.service = service;
        this.events = events;
        this.mapper = mapper;
    }

    @PostMapping("/report")
    public ResponseEntity<Void> report(@RequestHeader(value = "X-Agent-Token", required = false) String token, HttpServletRequest http) throws IOException {
        if (!service.enabled()) return ResponseEntity.notFound().build();
        if (!service.tokenOk(token)) {
            events.record(http.getRemoteAddr(), "ADMIN_PROBE", "POST", "/api/monitor/agent/report", 401, http.getHeader("User-Agent"), "Wrong monitoring token");
            return ResponseEntity.status(401).build();
        }
        byte[] raw = http.getInputStream().readNBytes(MAX_BYTES + 1);
        if (raw.length > MAX_BYTES) return ResponseEntity.status(413).build();
        Map<String, Object> body;
        try {
            body = mapper.readValue(raw, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        String refused = service.accept(http.getRemoteAddr(), body);
        if (refused != null) return ResponseEntity.badRequest().build();
        return ResponseEntity.noContent().build();
    }
}
