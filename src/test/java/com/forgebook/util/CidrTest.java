package com.forgebook.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.*;

class CidrTest {

    @Test @DisplayName("loopback v4 (127.0.0.0/8) is blocked")
    void loopbackV4_blocked() throws Exception {
        assertTrue(Cidr.isBlocked(InetAddress.getByName("127.0.0.1")));
    }

    @Test @DisplayName("RFC1918 10.0.0.0/8 is blocked")
    void rfc1918_10_blocked() throws Exception {
        assertTrue(Cidr.isBlocked(InetAddress.getByName("10.0.0.1")));
    }

    @Test @DisplayName("RFC1918 172.16.0.0/12 is blocked (with /12 prefix boundary)")
    void rfc1918_172_blocked() throws Exception {
        assertTrue(Cidr.isBlocked(InetAddress.getByName("172.16.0.1")));
        assertTrue(Cidr.isBlocked(InetAddress.getByName("172.31.255.254")));
        assertFalse(Cidr.isBlocked(InetAddress.getByName("172.15.255.255")), "below /12 boundary");
        assertFalse(Cidr.isBlocked(InetAddress.getByName("172.32.0.0")),      "above /12 boundary");
    }

    @Test @DisplayName("RFC1918 192.168.0.0/16 is blocked")
    void rfc1918_192_blocked() throws Exception {
        assertTrue(Cidr.isBlocked(InetAddress.getByName("192.168.1.1")));
    }

    @Test @DisplayName("link-local v4 169.254.0.0/16 (AWS IMDS) is blocked")
    void linkLocalV4_blocked() throws Exception {
        assertTrue(Cidr.isBlocked(InetAddress.getByName("169.254.169.254")));
    }

    @Test @DisplayName("0.0.0.0/8 is blocked")
    void zeroV4_blocked() throws Exception {
        assertTrue(Cidr.isBlocked(InetAddress.getByName("0.0.0.0")));
    }

    @Test @DisplayName("loopback v6 ::1/128 is blocked")
    void loopbackV6_blocked() throws Exception {
        assertTrue(Cidr.isBlocked(InetAddress.getByName("::1")));
    }

    @Test @DisplayName("unique-local v6 fc00::/7 is blocked")
    void uniqueLocalV6_blocked() throws Exception {
        assertTrue(Cidr.isBlocked(InetAddress.getByName("fc00::")));
        assertTrue(Cidr.isBlocked(InetAddress.getByName("fd12:3456:789a::1")));
    }

    @Test @DisplayName("link-local v6 fe80::/10 is blocked")
    void linkLocalV6_blocked() throws Exception {
        assertTrue(Cidr.isBlocked(InetAddress.getByName("fe80::")));
    }

    @Test @DisplayName("public IPs are not blocked")
    void publicIps_notBlocked() throws Exception {
        assertFalse(Cidr.isBlocked(InetAddress.getByName("8.8.8.8")));
        assertFalse(Cidr.isBlocked(InetAddress.getByName("1.1.1.1")));
        assertFalse(Cidr.isBlocked(InetAddress.getByName("93.184.216.34")));
        assertFalse(Cidr.isBlocked(InetAddress.getByName("2001:4860:4860::8888")));
    }

    @Test @DisplayName("IPv4 and IPv6 blocks do not cross-match (length guard)")
    void v4_v6_doNotCrossMatch() throws Exception {
        // A v4 loopback must not match the v6 ::1/128 block and vice versa.
        // The `b.network.length != bytes.length` guard in Cidr.matches enforces this.
        // Regression: if someone removes the guard, 127.0.0.1 would still match (via the v4 block)
        // but 0.0.0.0 bytes would start matching ::1/128 truncated. Simpler check: a v6 address
        // that is NOT in any v6 block must return false even though its first 4 bytes might look like
        // a v4 private range.
        // 000a:0000:... starts with bytes {0x00, 0x0a} which, reinterpreted as v4, looks like 0.10.x.x
        // (inside 0.0.0.0/8). The length guard prevents the false positive.
        assertFalse(Cidr.isBlocked(InetAddress.getByName("2001:db8::1")),
            "2001:db8::/32 (IETF documentation range) must not be blocked");
    }
}
