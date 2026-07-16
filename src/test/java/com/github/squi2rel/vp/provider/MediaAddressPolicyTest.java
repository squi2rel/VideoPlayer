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
}
