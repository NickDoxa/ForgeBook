package com.forgebook.ai.dto;

/** Error body shape on 4xx/5xx responses from Anthropic (RESEARCH §1.5). */
public final class ClaudeError {
    public String type;   // always "error"
    public ErrorBody error;

    public static final class ErrorBody {
        public String type;     // invalid_request_error | authentication_error | ... | overloaded_error
        public String message;
    }
}
