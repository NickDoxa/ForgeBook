package com.forgebook.util;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.function.Predicate;
import javax.net.ssl.*;

/**
 * Resolve-and-pin HTTPS fetcher that defends against SSRF and internal-network
 * exfiltration. Every URL fetched by ForgeBook (agent tools, CurseForge,
 * modinfo docs) MUST go through this class — there is no "raw HttpClient"
 * escape hatch in the codebase.
 *
 * Security gates applied per hop (<=3 redirects):
 *   1. Scheme MUST be https (else Reason.SCHEME).
 *   2. DNS resolution result MUST NOT be in {@link Cidr} blocklist (else Reason.PRIVATE_IP).
 *   3. TLS handshake pinned to resolved IP but SNI + cert validation use the
 *      ORIGINAL hostname (sidesteps JDK-8144566's custom-HostnameVerifier-
 *      disables-SNI bug; see SniSocketFactory).
 *   4. Response Content-Type MUST be in {text/html, text/plain, application/xhtml+xml}
 *      (else Reason.CONTENT_TYPE).
 *   5. Response body read is streaming with 1 MiB cap on accumulated bytes; Content-Length
 *      is IGNORED because servers can lie (else Reason.SIZE_CAP).
 *   6. Connect and read timeouts are 15 s each (else Reason.TIMEOUT).
 *   7. Chain length >3 (else Reason.REDIRECT_LIMIT).
 */
public final class SafeHttpFetcher {
    public static final long SIZE_CAP = 1_048_576L;     // 1 MB (D-26)
    public static final int TIMEOUT_MS = 15_000;        // 15 s (D-26)
    public static final int MAX_REDIRECTS = 3;          // D-23
    private static final Set<String> CONTENT_ALLOWLIST = Set.of(
        "text/html", "text/plain", "application/xhtml+xml");

    public record Result(String body, String contentType, URI finalUri) {}

    private final Predicate<InetAddress> cidrCheck;

    /** Production constructor — uses {@link Cidr#isBlocked(InetAddress)} as the CIDR gate. */
    public SafeHttpFetcher() { this(Cidr::isBlocked); }

    /**
     * Package-private test-only override for the CIDR check so HTTPS round-trip
     * tests can run against a localhost HttpsServer without being rejected as
     * PRIVATE_IP. Production code MUST use the no-arg constructor.
     */
    SafeHttpFetcher(Predicate<InetAddress> cidrCheck) {
        this.cidrCheck = cidrCheck;
    }

    public Result fetch(URI start) throws UnsafeUrlException, IOException {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!"https".equalsIgnoreCase(current.getScheme()))
                throw new UnsafeUrlException(UnsafeUrlException.Reason.SCHEME);
            String host = current.getHost();
            InetAddress resolved;
            try { resolved = InetAddress.getByName(host); }
            catch (UnknownHostException e) {
                throw new UnsafeUrlException(UnsafeUrlException.Reason.PRIVATE_IP); // collapse
            }
            if (cidrCheck.test(resolved))
                throw new UnsafeUrlException(UnsafeUrlException.Reason.PRIVATE_IP);

            URL pinnedUrl = buildUrlForIp(current, resolved);
            HttpsURLConnection conn = (HttpsURLConnection) pinnedUrl.openConnection();
            conn.setRequestProperty("Host", host);  // preserve original Host
            conn.setRequestProperty("User-Agent", "ForgeBook/0.1");
            conn.setRequestProperty("Accept", String.join(",", CONTENT_ALLOWLIST));
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false); // D-23 manual
            conn.setSSLSocketFactory(new SniSocketFactory(host));
            conn.setHostnameVerifier(new OriginalHostVerifier(host));

            int code;
            String ctHeader;
            try {
                conn.connect();
                code = conn.getResponseCode();

                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null) throw new UnsafeUrlException(
                        UnsafeUrlException.Reason.REDIRECT_LIMIT);
                    current = current.resolve(loc);
                    continue;  // re-validate on next loop iteration
                }

                ctHeader = conn.getHeaderField("Content-Type");
            } catch (SocketTimeoutException e) {
                throw new UnsafeUrlException(UnsafeUrlException.Reason.TIMEOUT);
            }

            // Validate Content-Type
            String mime = ctHeader == null ? "" :
                ctHeader.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!CONTENT_ALLOWLIST.contains(mime))
                throw new UnsafeUrlException(UnsafeUrlException.Reason.CONTENT_TYPE);

            // Streaming read with size cap (D-26: do NOT trust Content-Length).
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > SIZE_CAP)
                        throw new UnsafeUrlException(
                            UnsafeUrlException.Reason.SIZE_CAP);
                    out.write(buf, 0, n);
                }
                return new Result(out.toString(StandardCharsets.UTF_8), mime, current);
            } catch (SocketTimeoutException e) {
                throw new UnsafeUrlException(UnsafeUrlException.Reason.TIMEOUT);
            }
        }
        throw new UnsafeUrlException(UnsafeUrlException.Reason.REDIRECT_LIMIT);
    }

    /** Build a URL whose authority is the pinned IP; path/query preserved. */
    private static URL buildUrlForIp(URI u, InetAddress ip) throws MalformedURLException {
        String host = ip.getHostAddress();
        if (ip instanceof Inet6Address) host = "[" + host + "]";
        int port = u.getPort() == -1 ? 443 : u.getPort();
        String rest = (u.getRawPath() == null ? "" : u.getRawPath())
            + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        return new URL("https", host, port, rest);
    }

    /** SSLSocketFactory that forces SNI to the ORIGINAL hostname. */
    static final class SniSocketFactory extends SSLSocketFactory {
        private final String sniHost;
        private final SSLSocketFactory delegate;
        SniSocketFactory(String sniHost) {
            this.sniHost = sniHost;
            this.delegate = HttpsURLConnection.getDefaultSSLSocketFactory();
        }
        private SSLSocket withSni(Socket s) {
            SSLSocket ssl = (SSLSocket) s;
            SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(sniHost)));
            ssl.setSSLParameters(params);
            return ssl;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean auto) throws IOException {
            return withSni(delegate.createSocket(s, host, port, auto));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return withSni(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress la, int lp) throws IOException {
            return withSni(delegate.createSocket(host, port, la, lp));
        }
        @Override public Socket createSocket(InetAddress a, int p) throws IOException {
            return withSni(delegate.createSocket(a, p));
        }
        @Override public Socket createSocket(InetAddress a, int p, InetAddress la, int lp) throws IOException {
            return withSni(delegate.createSocket(a, p, la, lp));
        }
    }

    /** Verifier that validates cert against the original hostname (not the pinned IP). */
    static final class OriginalHostVerifier implements HostnameVerifier {
        private final String originalHost;
        OriginalHostVerifier(String originalHost) { this.originalHost = originalHost; }
        @Override public boolean verify(String unusedIpHost, SSLSession session) {
            HostnameVerifier defaultVerifier =
                HttpsURLConnection.getDefaultHostnameVerifier();
            return defaultVerifier.verify(originalHost, session);
        }
    }
}
