package io.github.hectorvent.floci.core.common.dns;

import java.util.List;

/**
 * The A records a {@link DnsRecordSource} owns for a query name, with the TTL the answering zone
 * publishes for them. A source that holds a TTL of its own supplies it, so a client caches the
 * answer for as long as the zone says rather than for a figure the DNS server picked.
 *
 * <p>Both members are per answer rather than per record: every address behind one Cloud Map
 * service shares that service's TTL, which is how Route 53 publishes a record set.
 */
public record DnsAnswer(List<String> addresses, int ttlSeconds) {

    /** What the DNS server publishes for a name whose zone declares no TTL of its own. */
    public static final int DEFAULT_TTL_SECONDS = 60;

    private static final DnsAnswer NONE = new DnsAnswer(List.of(), DEFAULT_TTL_SECONDS);

    public DnsAnswer {
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
        // A TTL is an unsigned 31-bit field, and RFC 2181 has a resolver treat anything with the
        // top bit set as zero, so a value outside the range is worse than no value at all. Cloud
        // Map's own range is the same 0 to 2147483647, so one that lands here came from a store
        // written by something other than a validated CreateService.
        if (ttlSeconds < 0) {
            ttlSeconds = DEFAULT_TTL_SECONDS;
        }
    }

    /** An owned name with no A addresses. Zone ownership is represented by Optional. */
    public static DnsAnswer none() {
        return NONE;
    }

    public boolean isEmpty() {
        return addresses.isEmpty();
    }
}
