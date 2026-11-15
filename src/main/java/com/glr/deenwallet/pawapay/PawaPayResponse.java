package com.glr.deenwallet.pawapay;

import com.fasterxml.jackson.databind.JsonNode;

/** One pawaPay answer. http = HTTP status; json = parsed body (a missing node when the body was empty). */
public record PawaPayResponse(int http, JsonNode json) {

    /** ACCEPTED / REJECTED / DUPLICATE_IGNORED for "initiate" calls; FOUND / NOT_FOUND for "check status" calls. */
    public String status() {
        return json.path("status").asText("");
    }

    public boolean accepted() {
        String s = status();
        return http == 200 && (s.equals("ACCEPTED") || s.equals("DUPLICATE_IGNORED"));
    }

    public boolean found() {
        return http == 200 && status().equals("FOUND");
    }

    public boolean notFound() {
        return http == 200 && status().equals("NOT_FOUND");
    }

    /** The transaction object of a "check status" answer. */
    public JsonNode data() {
        return json.path("data");
    }

    /** Status of the transaction itself (COMPLETED, FAILED, PROCESSING ...), from a "check status" answer. */
    public String dataStatus() {
        return data().path("status").asText("");
    }

    public String failureCode() {
        JsonNode f = json.path("failureReason");
        if (f.isMissingNode()) f = data().path("failureReason");
        return f.path("failureCode").asText("");
    }

    public String failureMessage() {
        JsonNode f = json.path("failureReason");
        if (f.isMissingNode()) f = data().path("failureReason");
        return f.path("failureMessage").asText("");
    }
}
