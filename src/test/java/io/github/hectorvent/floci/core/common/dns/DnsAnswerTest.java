package io.github.hectorvent.floci.core.common.dns;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsAnswerTest {

    @Test
    void noneIsEmptyAndCarriesTheDefaultTtl() {
        assertTrue(DnsAnswer.none().isEmpty());
        assertEquals(DnsAnswer.DEFAULT_TTL_SECONDS, DnsAnswer.none().ttlSeconds());
    }

    @Test
    void keepsATtlInsideTheRangeAnARecordCanCarry() {
        assertEquals(0, new DnsAnswer(List.of("10.0.0.1"), 0).ttlSeconds());
        assertEquals(Integer.MAX_VALUE,
                new DnsAnswer(List.of("10.0.0.1"), Integer.MAX_VALUE).ttlSeconds());
    }

    @Test
    void replacesATtlNoResolverCouldActOnWithTheDefault() {
        assertEquals(DnsAnswer.DEFAULT_TTL_SECONDS, new DnsAnswer(List.of("10.0.0.1"), -1).ttlSeconds());
    }

    @Test
    void treatsNoAddressesAsEmptyRatherThanFailing() {
        assertTrue(new DnsAnswer(null, 15).isEmpty());
        assertFalse(new DnsAnswer(List.of("10.0.0.1"), 15).isEmpty());
    }

    @Test
    void doesNotShareTheCallersList() {
        List<String> addresses = new ArrayList<>(List.of("10.0.0.1"));
        DnsAnswer answer = new DnsAnswer(addresses, 15);
        addresses.add("10.0.0.2");

        assertEquals(List.of("10.0.0.1"), answer.addresses());
        assertThrows(UnsupportedOperationException.class, () -> answer.addresses().add("10.0.0.3"));
    }
}
