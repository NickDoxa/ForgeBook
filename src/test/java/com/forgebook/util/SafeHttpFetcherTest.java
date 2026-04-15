package com.forgebook.util;

/*
 * Test keystore regeneration command (run from repo root):
 *
 *   keytool -genkeypair -alias forgebook-test -keyalg RSA -keysize 2048 \
 *     -dname "CN=localhost, OU=Test, O=ForgeBook, L=Test, ST=Test, C=US" \
 *     -keystore src/test/resources/forgebook-test.jks \
 *     -storepass forgebook -keypass forgebook -validity 3650 -storetype JKS
 *
 * The keystore ships under src/test/resources/forgebook-test.jks with
 * alias "forgebook-test", store-password "forgebook", key-password
 * "forgebook". CN=localhost so the self-signed cert validates when the
 * HttpsServer binds to 127.0.0.1 and the fetcher asks for "localhost".
 */

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.*;

import javax.net.ssl.*;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;

class SafeHttpFetcherTest {

    private static HttpsServer server;
    private static int port;
    private static SSLSocketFactory originalFactory;
    private static HostnameVerifier originalVerifier;

    @BeforeAll
    static void startServer() throws Exception {
        // 1. Load the test keystore.
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = SafeHttpFetcherTest.class
                .getResourceAsStream("/forgebook-test.jks")) {
            if (in == null) throw new IllegalStateException(
                "forgebook-test.jks missing from test resources");
            ks.load(in, "forgebook".toCharArray());
        }

        // 2. KeyManager for the server (presents the self-signed cert).
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "forgebook".toCharArray());

        // 3. Trust-all TrustManager for the client side (self-signed cert).
        TrustManager[] trustAll = new TrustManager[] {
            new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
        };

        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), trustAll, null);

        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, trustAll, null);

        // 4. Bind HttpsServer to 127.0.0.1 on an ephemeral port.
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverCtx));
        server.start();
        port = server.getAddress().getPort();

        // 5. Save original defaults, install trust-all for the client side.
        originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        originalVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        HttpsURLConnection.setDefaultSSLSocketFactory(clientCtx.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
        if (originalFactory != null) HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory);
        if (originalVerifier != null) HttpsURLConnection.setDefaultHostnameVerifier(originalVerifier);
    }

    @BeforeEach
    void freshHandlers() {
        // Remove any contexts from previous tests.
        for (String ctx : new String[] {"/loop", "/big", "/big-lying", "/json", "/slow", "/ok"}) {
            try { server.removeContext(ctx); } catch (IllegalArgumentException ignored) {}
        }
    }

    @Test
    void scheme_rejected() {
        SafeHttpFetcher f = new SafeHttpFetcher();
        UnsafeUrlException ex = assertThrows(UnsafeUrlException.class,
            () -> f.fetch(URI.create("http://example.com/")));
        assertEquals(UnsafeUrlException.Reason.SCHEME, ex.reason());
    }

    @Test
    void privateIp_rejected_for_127_0_0_1() {
        SafeHttpFetcher f = new SafeHttpFetcher();
        UnsafeUrlException ex = assertThrows(UnsafeUrlException.class,
            () -> f.fetch(URI.create("https://127.0.0.1/")));
        assertEquals(UnsafeUrlException.Reason.PRIVATE_IP, ex.reason());
    }

    @Test
    void redirectLimit_exceeded() {
        server.createContext("/loop", exch -> {
            exch.getResponseHeaders().add("Location", "/loop");
            exch.sendResponseHeaders(302, -1);
            exch.close();
        });
        SafeHttpFetcher f = new SafeHttpFetcher(addr -> false); // pkg-private test ctor
        UnsafeUrlException ex = assertThrows(UnsafeUrlException.class,
            () -> f.fetch(URI.create("https://localhost:" + port + "/loop")));
        assertEquals(UnsafeUrlException.Reason.REDIRECT_LIMIT, ex.reason());
    }

    @Test
    void sizeCap_enforced() {
        byte[] big = new byte[2 * 1024 * 1024]; // 2 MiB (> SIZE_CAP)

        // Variant A: truthful Content-Length (2 MiB advertised, 2 MiB sent).
        server.createContext("/big", exch -> {
            exch.getResponseHeaders().add("Content-Type", "text/html");
            exch.sendResponseHeaders(200, big.length);
            exch.getResponseBody().write(big);
            exch.close();
        });
        // Variant B: lying Content-Length (100 advertised, 2 MiB streamed) — proves
        // the fetcher ignores Content-Length and enforces via the streaming counter.
        server.createContext("/big-lying", exch -> {
            exch.getResponseHeaders().add("Content-Type", "text/html");
            // chunked transfer (responseLength = 0 per HttpServer convention)
            exch.sendResponseHeaders(200, 0);
            exch.getResponseBody().write(big);
            exch.close();
        });
        SafeHttpFetcher f = new SafeHttpFetcher(addr -> false);

        UnsafeUrlException exA = assertThrows(UnsafeUrlException.class,
            () -> f.fetch(URI.create("https://localhost:" + port + "/big")));
        assertEquals(UnsafeUrlException.Reason.SIZE_CAP, exA.reason(),
            "truthful Content-Length variant: streaming cap must fire");

        UnsafeUrlException exB = assertThrows(UnsafeUrlException.class,
            () -> f.fetch(URI.create("https://localhost:" + port + "/big-lying")));
        assertEquals(UnsafeUrlException.Reason.SIZE_CAP, exB.reason(),
            "lying Content-Length variant: streaming cap must still fire");
    }

    @Test
    void contentType_rejected() {
        server.createContext("/json", exch -> {
            exch.getResponseHeaders().add("Content-Type", "application/json");
            byte[] body = "{}".getBytes();
            exch.sendResponseHeaders(200, body.length);
            exch.getResponseBody().write(body);
            exch.close();
        });
        SafeHttpFetcher f = new SafeHttpFetcher(addr -> false);
        UnsafeUrlException ex = assertThrows(UnsafeUrlException.class,
            () -> f.fetch(URI.create("https://localhost:" + port + "/json")));
        assertEquals(UnsafeUrlException.Reason.CONTENT_TYPE, ex.reason());
    }

    @Test
    @Timeout(25)
    void timeout_enforced() {
        server.createContext("/slow", exch -> {
            try { Thread.sleep(20_000); } catch (InterruptedException ignored) {}
            exch.sendResponseHeaders(200, 0);
            exch.close();
        });
        SafeHttpFetcher f = new SafeHttpFetcher(addr -> false);
        long t0 = System.currentTimeMillis();
        UnsafeUrlException ex = assertThrows(UnsafeUrlException.class,
            () -> f.fetch(URI.create("https://localhost:" + port + "/slow")));
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(UnsafeUrlException.Reason.TIMEOUT, ex.reason());
        assertTrue(elapsed < 20_000, "should fail before server wakes, was " + elapsed + "ms");
    }

    @Test
    void fetch_success_returnsBody() throws Exception {
        byte[] body = "<html><body>hello</body></html>".getBytes();
        server.createContext("/ok", exch -> {
            exch.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exch.sendResponseHeaders(200, body.length);
            exch.getResponseBody().write(body);
            exch.close();
        });
        SafeHttpFetcher f = new SafeHttpFetcher(addr -> false);
        SafeHttpFetcher.Result r = f.fetch(URI.create("https://localhost:" + port + "/ok"));
        assertEquals("<html><body>hello</body></html>", r.body());
        assertEquals("text/html", r.contentType());
    }
}
