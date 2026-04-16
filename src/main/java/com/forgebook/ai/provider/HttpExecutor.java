package com.forgebook.ai.provider;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

/**
 * Injectable HTTP seam (RESEARCH §9.4). Production = JDK HttpClient::send.
 * Tests inject a stub returning canned HttpResponse<String>.
 */
@FunctionalInterface
public interface HttpExecutor {
    HttpResponse<String> send(HttpRequest req) throws Exception;

    static HttpExecutor production() {
        HttpClient client = HttpClient.newHttpClient();
        return req -> client.send(req, BodyHandlers.ofString());
    }
}
