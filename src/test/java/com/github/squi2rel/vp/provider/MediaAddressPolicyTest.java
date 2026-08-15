package com.github.squi2rel.vp.provider;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaAddressPolicyTest {
    @Test
    void blocksIpv4MappedLoopbackAddresses() throws Exception {
        InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");

        assertTrue(MediaAddressPolicy.isBlocked(mapped));
        assertFalse(MediaAddressPolicy.isAllowed("http://mapped.example", ignored -> new InetAddress[]{mapped}));
    }

    @Test
    void blocksPrivateAndMetadataRanges() throws Exception {
        assertTrue(MediaAddressPolicy.isBlocked(InetAddress.getByName("10.0.0.1")));
        assertTrue(MediaAddressPolicy.isBlocked(InetAddress.getByName("192.168.1.1")));
        assertTrue(MediaAddressPolicy.isBlocked(InetAddress.getByName("169.254.169.254")));
        assertTrue(MediaAddressPolicy.isBlocked(InetAddress.getByName("192.0.0.8")));
        assertFalse(MediaAddressPolicy.isAllowed("http://internal.example", ignored -> new InetAddress[]{
                InetAddress.getByName("10.1.2.3")
        }));
    }

    @Test
    void allowsRemoteDownloadHostWhenProxyOwnsDnsResolution() throws Exception {
        assertTrue(MediaAddressPolicy.isAllowedForDownload(
                "https://github.com/example/runtime.zip",
                true,
                ignored -> new InetAddress[]{InetAddress.getByName("198.18.0.156")}
        ));
    }

    @Test
    void blocksProxySyntheticRangeWhenUriHostIsAnIpLiteral() {
        MediaAddressPolicy.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByName(host)};

        assertFalse(MediaAddressPolicy.isAllowedForDownload("https://198.18.0.156/runtime.zip", true, resolver));
        assertFalse(MediaAddressPolicy.isAllowedForDownload("https://198.19.255.254/runtime.zip", true, resolver));
    }

    @Test
    void doesNotApplyProxySyntheticExemptionToIpv6Literals() throws Exception {
        assertFalse(MediaAddressPolicy.isAllowedForDownload(
                "https://[2001:4860:4860::8888]/runtime.zip",
                true,
                ignored -> new InetAddress[]{InetAddress.getByName("198.18.0.156")}
        ));
    }

    @Test
    void blocksAlternateIpLiteralFormsWhenProxyIsConfigured() {
        MediaAddressPolicy.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByName(host)};

        assertFalse(MediaAddressPolicy.isAllowedForDownload("https://3323068417/runtime.zip", true, resolver));
        assertFalse(MediaAddressPolicy.isAllowedForDownload(
                "https://[::ffff:198.18.0.1]/runtime.zip",
                true,
                resolver
        ));
        assertFalse(MediaAddressPolicy.isAllowedForDownload(
                "https://[::ffff:c612:1]/runtime.zip",
                true,
                resolver
        ));
    }

    @Test
    void blocksOtherPrivateAddressesWhenProxyIsConfigured() throws Exception {
        assertFalse(MediaAddressPolicy.isAllowedForDownload(
                "https://internal.example/runtime.zip",
                true,
                ignored -> new InetAddress[]{InetAddress.getByName("10.1.2.3")}
        ));
    }
}
