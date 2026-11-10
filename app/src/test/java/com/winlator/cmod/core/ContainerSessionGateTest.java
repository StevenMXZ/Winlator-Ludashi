package com.winlator.cmod.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class ContainerSessionGateTest {
    @Test public void wineAndOfflineEditsCannotShareAPrefix() {
        assertTrue(ContainerSessionGate.start(12001));
        assertFalse(ContainerSessionGate.edit(12001));
        ContainerSessionGate.stop(12001);
        assertTrue(ContainerSessionGate.edit(12001));
        assertFalse(ContainerSessionGate.start(12001));
        ContainerSessionGate.edited(12001);
        assertFalse(ContainerSessionGate.anyBusy());
    }
}
