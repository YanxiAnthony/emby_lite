package com.example.embylite;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionGateTest {
    @Test public void oldAccountWorkIsRejectedAfterLogoutAndNewLogin() {
        SessionGate gate = new SessionGate();
        int oldAccount = gate.current();
        gate.invalidate();
        int newAccount = gate.invalidate();
        assertFalse(gate.accepts(oldAccount));
        assertTrue(gate.accepts(newAccount));
    }

    @Test public void replacementLoginCannotBeOverwrittenByFirstResponse() {
        SessionGate gate = new SessionGate();
        int first = gate.invalidate();
        int second = gate.invalidate();
        assertFalse(gate.accepts(first));
        assertTrue(gate.accepts(second));
    }
}
