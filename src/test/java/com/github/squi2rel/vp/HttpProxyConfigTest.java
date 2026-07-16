package com.github.squi2rel.vp;

import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpProxyConfigTest {
    @Test
    void parsesDisabledAndDefaultPorts() {
        HttpProxyConfig disabled = HttpProxyConfig.parse(" ");
        HttpProxyConfig http = HttpProxyConfig.parse("proxy.example");
        HttpProxyConfig https = HttpProxyConfig.parse("https://proxy.example");

        assertFalse(disabled.enabled());
        assertEquals(80, http.port());
        assertEquals(443, https.port());
        assertEquals("proxy.example", http.host());
    }

    @Test
    void authenticatesOnlyMatchingProxyChallenges() throws Exception {
        HttpProxyConfig config = HttpProxyConfig.parse("http://proxy-user:part1:part2@proxy.example:8080");
        Authenticator authenticator = config.authenticator();
        URL source = URI.create("https://media.example/video").toURL();

        PasswordAuthentication matching = request(authenticator, "PROXY.EXAMPLE", 8080, source, Authenticator.RequestorType.PROXY);

        assertNotNull(matching);
        assertEquals("proxy-user", matching.getUserName());
        assertArrayEquals("part1:part2".toCharArray(), matching.getPassword());
        assertNull(request(authenticator, "source.example", 8080, source, Authenticator.RequestorType.PROXY));
        assertNull(request(authenticator, "proxy.example", 8081, source, Authenticator.RequestorType.PROXY));
        assertNull(request(authenticator, "proxy.example", 8080, source, Authenticator.RequestorType.SERVER));
    }

    @Test
    void omitsAuthenticatorWithoutUsername() {
        assertFalse(HttpProxyConfig.parse("http://proxy.example:8080").authenticated());
        assertNull(HttpProxyConfig.parse("http://proxy.example:8080").authenticator());
    }

    @Test
    void redactsCredentialsFromDisplayAndParseErrors() {
        HttpProxyConfig config = HttpProxyConfig.parse("http://proxy-user:proxy-password@proxy.example:8080");

        assertEquals("proxy.example:8080 (authenticated=true)", config.toString());
        assertFalse(config.toString().contains("proxy-user"));
        assertFalse(config.toString().contains("proxy-password"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HttpProxyConfig.parse("http://secret-user:secret-password@proxy.example:abc"));
        assertFalse(error.toString().contains("secret-user"));
        assertFalse(error.toString().contains("secret-password"));
    }

    @Test
    void rejectsUnsupportedSchemeAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> HttpProxyConfig.parse("socks://proxy.example:1080"));
        assertThrows(IllegalArgumentException.class, () -> HttpProxyConfig.parse("http://proxy.example:0"));
        assertThrows(IllegalArgumentException.class, () -> HttpProxyConfig.parse("http://proxy.example:70000"));
    }

    private static PasswordAuthentication request(Authenticator authenticator, String host, int port, URL url,
                                                  Authenticator.RequestorType type) {
        return Authenticator.requestPasswordAuthentication(
                authenticator, host, null, port, "http", "proxy", "basic", url, type);
    }
}
