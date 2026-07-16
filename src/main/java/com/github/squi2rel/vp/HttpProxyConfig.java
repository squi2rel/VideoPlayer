package com.github.squi2rel.vp;

import com.github.squi2rel.vp.i18n.TranslatableIllegalArgumentException;
import com.github.squi2rel.vp.i18n.VpTranslation;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;
import java.util.Objects;

public final class HttpProxyConfig {
    private static final HttpProxyConfig DISABLED = new HttpProxyConfig(false, "", 0, "", "");

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    private HttpProxyConfig(boolean enabled, String host, int port, String username, String password) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public static HttpProxyConfig parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return DISABLED;
        URI uri;
        try {
            uri = URI.create(value.contains("://") ? value : "http://" + value);
        } catch (RuntimeException ignored) {
            throw invalidProxy();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new TranslatableIllegalArgumentException(VpTranslation.of(
                    "error.videoplayer.native.proxy_http_only", "Only HTTP proxies are supported"));
        }
        String host = normalizeHost(uri.getHost());
        if (host.isBlank()) {
            throw new TranslatableIllegalArgumentException(VpTranslation.of(
                    "error.videoplayer.native.proxy_host_missing", "Proxy host is missing"));
        }
        int configuredPort = uri.getPort();
        if (configuredPort == 0 || configuredPort > 65535) {
            throw new TranslatableIllegalArgumentException(VpTranslation.of(
                    "error.videoplayer.native.proxy_port_invalid", "Invalid proxy port"));
        }
        int port = configuredPort < 0 ? (scheme.equals("https") ? 443 : 80) : configuredPort;
        String username = "";
        String password = "";
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int separator = userInfo.indexOf(':');
            if (separator < 0) {
                username = userInfo;
            } else {
                username = userInfo.substring(0, separator);
                password = userInfo.substring(separator + 1);
            }
        }
        return new HttpProxyConfig(true, host, port, username, password);
    }

    public boolean enabled() {
        return enabled;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean authenticated() {
        return !username.isEmpty();
    }

    public HttpClient.Builder configure(HttpClient.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        if (!enabled) return builder;
        builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(host, port)));
        Authenticator authenticator = authenticator();
        if (authenticator != null) builder.authenticator(authenticator);
        return builder;
    }

    Authenticator authenticator() {
        if (!authenticated()) return null;
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) return null;
                if (!host.equals(normalizeHost(getRequestingHost()))) return null;
                if (getRequestingPort() != port) return null;
                return new PasswordAuthentication(username, password.toCharArray());
            }
        };
    }

    public String redacted() {
        if (!enabled) return "disabled";
        String formattedHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return formattedHost + ":" + port + " (authenticated=" + authenticated() + ")";
    }

    @Override
    public String toString() {
        return redacted();
    }

    private static String normalizeHost(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.length() > 1 && normalized.charAt(0) == '[' && normalized.charAt(normalized.length() - 1) == ']') {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static TranslatableIllegalArgumentException invalidProxy() {
        return new TranslatableIllegalArgumentException(VpTranslation.of(
                "error.videoplayer.native.invalid_proxy", "Invalid proxy configuration"));
    }
}
