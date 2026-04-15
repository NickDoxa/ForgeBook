package com.forgebook.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Static CIDR blocklist matcher used by {@link SafeHttpFetcher} to refuse
 * connections to private / internal / cloud-metadata address ranges.
 *
 * The 9 blocked ranges (in list order):
 *   IPv4: 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16,
 *         169.254.0.0/16, 0.0.0.0/8
 *   IPv6: ::1/128, fc00::/7, fe80::/10
 *
 * The {@code network.length != bytes.length} guard in {@link #matches} prevents
 * an IPv4 address from accidentally matching an IPv6 block (and vice versa).
 */
public final class Cidr {
    private Cidr() {}

    private record Block(byte[] network, int prefixBits) {}
    private static final List<Block> BLOCKED = List.of(
        parse("127.0.0.0/8"), parse("10.0.0.0/8"),
        parse("172.16.0.0/12"), parse("192.168.0.0/16"),
        parse("169.254.0.0/16"), parse("0.0.0.0/8"),
        parse("::1/128"), parse("fc00::/7"), parse("fe80::/10")
    );

    public static boolean isBlocked(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        for (Block b : BLOCKED) {
            if (b.network.length != bytes.length) continue; // v4 vs v6
            if (matches(bytes, b.network, b.prefixBits)) return true;
        }
        return false;
    }

    private static boolean matches(byte[] addr, byte[] net, int prefix) {
        int fullBytes = prefix / 8;
        int partialBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) if (addr[i] != net[i]) return false;
        if (partialBits == 0) return true;
        int mask = 0xFF << (8 - partialBits);
        return (addr[fullBytes] & mask) == (net[fullBytes] & mask);
    }

    private static Block parse(String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress a = InetAddress.getByName(parts[0]);
            return new Block(a.getAddress(), Integer.parseInt(parts[1]));
        } catch (UnknownHostException e) { throw new RuntimeException(e); }
    }
}
