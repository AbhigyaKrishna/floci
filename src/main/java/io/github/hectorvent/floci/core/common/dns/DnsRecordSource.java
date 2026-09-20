package io.github.hectorvent.floci.core.common.dns;

import java.util.List;

/**
 * A source of A records that {@link EmbeddedDnsServer} consults before forwarding a query
 * upstream. Implemented by services that own a private DNS zone, so the DNS server stays
 * independent of them: it discovers implementations through CDI rather than importing one.
 *
 * <p>An implementation answers only for names inside a zone it owns and returns an empty
 * list for everything else, which leaves the query to the next source or the upstream
 * resolvers. It runs on the DNS packet path, outside any request context.
 */
public interface DnsRecordSource {

    /**
     * Resolves a query name to IPv4 addresses, or an empty list when this source does not
     * own the name. The name arrives without a trailing dot and in the case the client sent.
     */
    List<String> resolveIpv4(String name);
}
