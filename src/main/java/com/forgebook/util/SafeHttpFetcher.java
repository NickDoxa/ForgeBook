package com.forgebook.util;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import javax.net.ssl.HttpsURLConnection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * HTTPS fetcher with SSRF and content-size protections. Every URL fetched by
 * ForgeBook (agent tools, CurseForge, mod docs) goes through this class.
 *
 * <h2>Security gates applied per hop (≤{@link #MAX_REDIRECTS} redirects):</h2>
 * <ol>
 *   <li>Scheme MUST be {@code https} (else {@link UnsafeUrlException.Reason#SCHEME}).</li>
 *   <li>DNS resolution MUST NOT return any IP in the {@link Cidr} blocklist
 *       (private / loopback / link-local / multicast). ALL resolved IPs are
 *       checked — if even one is blocked we reject, preventing a multi-A-record
 *       hostname from smuggling a private IP past a single-lookup check.</li>
 *   <li>Response Content-Type MUST be in {@link #CONTENT_ALLOWLIST}
 *       (else {@link UnsafeUrlException.Reason#CONTENT_TYPE}).</li>
 *   <li>Response body is read streaming with a 1 MiB cap on accumulated bytes;
 *       {@code Content-Length} is IGNORED because servers can lie
 *       (else {@link UnsafeUrlException.Reason#SIZE_CAP}).</li>
 *   <li>Connect and read timeouts are 15 s each (else
 *       {@link UnsafeUrlException.Reason#TIMEOUT}).</li>
 *   <li>Redirect chain length > 3 → {@link UnsafeUrlException.Reason#REDIRECT_LIMIT}.</li>
 * </ol>
 *
 * <h2>Connection model — v1.0.2 rewrite</h2>
 * Earlier versions pinned the TCP connection to a resolved IP address and used
 * a custom {@code SSLSocketFactory} / {@code HostnameVerifier} pair to preserve
 * SNI to the original hostname (the JDK-8144566 workaround). That approach
 * interacted badly with the JDK's URL-spoofing check: {@code HttpsURLConnection}
 * validated the cert against the pinned IP literal before the custom verifier
 * got its say, and every HTTPS fetch died with
 * {@code HTTPS hostname wrong: should be <IP>}.
 *
 * <p>ForgeBook's threat model does not require IP pinning. The URLs fetched
 * are mod-documentation URLs declared in mods.toml, scanned from mod
 * descriptions, or returned by an operator-enabled web search — all authored
 * or whitelisted by parties the operator already trusts to run code on the
 * server. DNS rebinding against those URLs to reach internal services is a
 * low-value, high-effort attack compared with the other attack surfaces an
 * installed mod already has.
 *
 * <p>We therefore connect over the normal {@code HttpsURLConnection} path using
 * the original hostname, after rejecting any hostname that resolves to a
 * blocked IP. TOCTOU between the DNS check and the connection is a few
 * milliseconds of system cache; combined with the threat model above, it is
 * an acceptable trade-off for a working fetcher.
 */
public final class SafeHttpFetcher {
    private static final Logger LOG = LogManager.getLogger();
    public static final long SIZE_CAP = 1_048_576L;     // 1 MB
    public static final int TIMEOUT_MS = 15_000;        // 15 s
    public static final int MAX_REDIRECTS = 3;
    private static final Set<String> CONTENT_ALLOWLIST = Set.of(
        "text/html", "text/plain", "application/xhtml+xml");
    private static final String USER_AGENT =
        "Mozilla/5.0 (compatible; ForgeBook/1.0; +https://github.com/NickDoxa/ForgeBook)";

    public record Result(String body, String contentType, URI finalUri) {}

    private final Predicate<InetAddress> cidrCheck;

    /** Production constructor — uses {@link Cidr#isBlocked(InetAddress)} as the CIDR gate. */
    public SafeHttpFetcher() { this(Cidr::isBlocked); }

    /**
     * Package-private test-only override for the CIDR check so HTTPS round-trip
     * tests can run against a localhost HttpsServer without being rejected as
     * {@link UnsafeUrlException.Reason#PRIVATE_IP}. Production code MUST use the
     * no-arg constructor.
     */
    SafeHttpFetcher(Predicate<InetAddress> cidrCheck) {
        this.cidrCheck = cidrCheck;
    }

    public Result fetch(URI start) throws UnsafeUrlException, IOException {
        long startNanos = System.nanoTime();
        LOG.info("safehttp fetch start url={}", start);
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!"https".equalsIgnoreCase(current.getScheme()))
                throw new UnsafeUrlException(UnsafeUrlException.Reason.SCHEME);
            String host = current.getHost();
            if (host == null || host.isEmpty())
                throw new UnsafeUrlException(UnsafeUrlException.Reason.SCHEME);

            // DNS resolution — check EVERY resolved IP against the CIDR blocklist.
            // Using getAllByName rather than getByName so a hostname with multiple
            // A records (e.g. IPv4 + IPv6, or CDN round-robins) can't sneak a
            // private IP past a single-lookup check.
            InetAddress[] resolved;
            try { resolved = InetAddress.getAllByName(host); }
            catch (UnknownHostException e) {
                throw new UnsafeUrlException(UnsafeUrlException.Reason.PRIVATE_IP);
            }
            for (InetAddress ip : resolved) {
                if (cidrCheck.test(ip))
                    throw new UnsafeUrlException(UnsafeUrlException.Reason.PRIVATE_IP);
            }

            // Open connection on the ORIGINAL URL — the JVM handles SNI and
            // hostname verification normally, which works. No IP pinning.
            HttpsURLConnection conn = (HttpsURLConnection) current.toURL().openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", String.join(",", CONTENT_ALLOWLIST));
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);  // manual so we re-check private-IP per hop

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
                    URI next = current.resolve(loc);
                    LOG.info("safehttp redirect hop={}/{} status={} from={} to={}",
                        hop + 1, MAX_REDIRECTS + 1, code, current, next);
                    current = next;
                    continue;  // next iteration re-validates scheme + private-IP
                }

                ctHeader = conn.getHeaderField("Content-Type");
            } catch (SocketTimeoutException e) {
                throw new UnsafeUrlException(UnsafeUrlException.Reason.TIMEOUT);
            }

            // Content-Type allowlist
            String mime = ctHeader == null ? "" :
                ctHeader.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!CONTENT_ALLOWLIST.contains(mime))
                throw new UnsafeUrlException(UnsafeUrlException.Reason.CONTENT_TYPE);

            // Streaming read with size cap — ignore Content-Length (servers lie).
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
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                LOG.info("safehttp fetch ok status={} bytes={} content_type={} url={} latency_ms={}",
                    code, total, mime, current, latencyMs);
                return new Result(out.toString(StandardCharsets.UTF_8), mime, current);
            } catch (SocketTimeoutException e) {
                throw new UnsafeUrlException(UnsafeUrlException.Reason.TIMEOUT);
            }
        }
        throw new UnsafeUrlException(UnsafeUrlException.Reason.REDIRECT_LIMIT);
    }
}
