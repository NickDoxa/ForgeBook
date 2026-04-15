package com.forgebook.util.log;

import java.util.regex.Pattern;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;

/**
 * CFG-05 / D-16: rewrites any log message containing API-key-shaped substrings
 * to &lt;redacted&gt;. Registered as a RewritePolicy plugin; wired via
 * &lt;Rewrite&gt; appender in log4j2.xml that wraps Forge's console appenders.
 *
 * Defense-in-depth with ApiKey.toString() — catches logs from libraries we don't
 * control (HttpClient, jsoup, Forge, Netty) that have no knowledge of ApiKey.
 *
 * Patterns (D-16):
 *   - Authorization header values
 *   - x-api-key header values
 *   - sk-ant-[A-Za-z0-9_-]+ (Anthropic)
 *   - sk-proj-[A-Za-z0-9_-]+ (OpenAI project keys)
 *   - api_key=... query param
 *
 * Implemented as RewritePolicy (NOT Filter): Log4j2's Filter API gates pass/block,
 * not rewrite. RewritePolicy is the production-correct shape for message rewriting
 * and composes with a RewriteAppender that wraps the existing Forge appenders.
 */
@Plugin(name = "ApiKeyScrub", category = "Core", elementType = "rewritePolicy", printObject = true)
public final class ApiKeyScrubFilter implements RewritePolicy {

    // Package-private for unit testing.
    static final Pattern AUTHZ_HEADER   = Pattern.compile("(?i)(Authorization\\s*[:=]\\s*)\\S+");
    static final Pattern XAPIKEY_HEADER = Pattern.compile("(?i)(x-api-key\\s*[:=]\\s*)\\S+");
    static final Pattern SK_ANT         = Pattern.compile("sk-ant-[A-Za-z0-9_\\-]+");
    static final Pattern SK_PROJ        = Pattern.compile("sk-proj-[A-Za-z0-9_\\-]+");
    static final Pattern API_KEY_QP     = Pattern.compile("(?i)(api_key=)[^&\\s]+");

    private ApiKeyScrubFilter() {}

    @PluginFactory
    public static ApiKeyScrubFilter createPolicy() {
        return new ApiKeyScrubFilter();
    }

    @Override
    public LogEvent rewrite(LogEvent event) {
        Message original = event.getMessage();
        String formatted = original.getFormattedMessage();
        String scrubbed = scrub(formatted);
        if (scrubbed.equals(formatted)) {
            return event;
        }
        return new Log4jLogEvent.Builder(event)
            .setMessage(new SimpleMessage(scrubbed))
            .build();
    }

    /** Pure function — also used directly by unit tests and by ad-hoc callers. */
    public static String scrub(String s) {
        if (s == null) return null;
        String out = s;
        out = AUTHZ_HEADER.matcher(out).replaceAll("$1<redacted>");
        out = XAPIKEY_HEADER.matcher(out).replaceAll("$1<redacted>");
        out = SK_ANT.matcher(out).replaceAll("sk-ant-<redacted>");
        out = SK_PROJ.matcher(out).replaceAll("sk-proj-<redacted>");
        out = API_KEY_QP.matcher(out).replaceAll("$1<redacted>");
        return out;
    }
}
