package com.vcampus.client.fx.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestGateTest {
    @Test void newerRequestInvalidatesOlderResult() {
        RequestGate gate = new RequestGate();
        long old = gate.next();
        long current = gate.next();
        assertFalse(gate.accepts(old));
        assertTrue(gate.accepts(current));
        gate.close();
        assertFalse(gate.accepts(current));
    }
}
