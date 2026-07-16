package com.github.squi2rel.vp.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

final class HttpResponseBody {
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private HttpResponseBody() {
    }

    static String read(HttpResponse<InputStream> response) throws IOException {
        try (InputStream input = response.body()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (total > MAX_BYTES - read) {
                    throw new IOException("HTTP response exceeds " + MAX_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
