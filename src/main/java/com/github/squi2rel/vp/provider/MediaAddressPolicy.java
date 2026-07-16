package com.github.squi2rel.vp.provider;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

public final class MediaAddressPolicy {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "rtsp", "rtsps", "rtspt", "rtp", "mms");

    private MediaAddressPolicy() {
    }

    public static boolean isSyntacticallyAllowed(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null
                    && ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                    && uri.getUserInfo() == null
                    && host != null
                    && !host.isBlank();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static boolean isAllowed(String raw) {
        return isAllowed(raw, InetAddress::getAllByName);
    }

    static boolean isAllowed(String raw, HostResolver resolver) {
        if (!isSyntacticallyAllowed(raw)) return false;
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        try {
            InetAddress[] addresses = resolver.resolve(uri.getHost());
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (isBlocked(address)) return false;
            }
            return true;
        } catch (UnknownHostException ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public static boolean isBlocked(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 0 || first == 10 || first == 127 || first >= 224) return true;
            if (first == 100 && second >= 64 && second <= 127) return true;
            if (first == 169 && second == 254) return true;
            if (first == 192 && second == 0) return true;
            if (first == 198 && (second == 18 || second == 19 || second == 51)) return true;
            if (first == 203 && second == 0) return true;
        }
        if (address instanceof Inet6Address) {
            byte[] ipv6 = address.getAddress();
            boolean ipv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (ipv6[i] != 0) {
                    ipv4Mapped = false;
                    break;
                }
            }
            boolean ipv4Compatible = ipv4Mapped && ipv6[10] == 0 && ipv6[11] == 0;
            ipv4Mapped = ipv4Mapped && (ipv6[10] & 0xff) == 0xff && (ipv6[11] & 0xff) == 0xff;
            if (ipv4Mapped || ipv4Compatible) {
                try {
                    return isBlocked(InetAddress.getByAddress(new byte[]{ipv6[12], ipv6[13], ipv6[14], ipv6[15]}));
                } catch (UnknownHostException ignored) {
                    return true;
                }
            }
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return true;
        }
        return false;
    }
}
