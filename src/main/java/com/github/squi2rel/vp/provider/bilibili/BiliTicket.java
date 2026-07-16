package com.github.squi2rel.vp.provider.bilibili;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BiliTicket {
    private static final int MAX_RESPONSE_BYTES = 1 * 1024 * 1024;
    /**
     * Convert a byte array to a hex string.
     *
     * @param bytes The byte array to convert.
     * @return The hex string representation of the given byte array.
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * Generate a HMAC-SHA256 hash of the given message string using the given key
     * string.
     *
     * @param key     The key string to use for the HMAC-SHA256 hash.
     * @param message The message string to hash.
     * @throws Exception If an error occurs during the HMAC-SHA256 hash generation.
     * @return The HMAC-SHA256 hash of the given message string using the given key
     *         string.
     */
    public static String hmacSha256(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    /**
     * Get a Bilibili web ticket for the given CSRF token.
     *
     * @param csrf The CSRF token to use for the web ticket, can be {@code null} or
     *             empty.
     * @return The Bilibili web ticket raw response for the given CSRF token.
     * @throws Exception If an error occurs during the web ticket generation.
     * @see <a href="https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/misc/sign/bili_ticket.md">...</a>
     */
    public static String getBiliTicket(String csrf) throws Exception {
        // params
        long ts = System.currentTimeMillis() / 1000;
        String hexSign = hmacSha256("XgwSnGZ1p", "ts" + ts);
        HttpURLConnection conn = getHttpURLConnection(csrf, hexSign, ts);
        InputStream in = conn.getInputStream();
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                if (total > MAX_RESPONSE_BYTES - read) throw new IOException("Bilibili ticket response is too large");
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static @NotNull HttpURLConnection getHttpURLConnection(String csrf, String hexSign, long ts) throws IOException, URISyntaxException {
        String url = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket" + '?' +
                "key_id=ec02" + '&' +
                "hexsign=" + hexSign + '&' +
                "context[ts]=" + ts + '&' +
                "csrf=" + (csrf == null ? "" : csrf);
        // request
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.addRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0");
        return conn;
    }
}
