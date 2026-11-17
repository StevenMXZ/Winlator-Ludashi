package com.winlator.cmod.core;

/** Serializes prefix mutations with Wine startup. Leases last only for the operation/session. */
public final class ContainerSessionGate {
    private static final java.util.Set<Integer> sessions = new java.util.HashSet<>();
    private static final java.util.Set<Integer> edits = new java.util.HashSet<>();
    private static boolean maintenance;
    private static int transfers;
    private ContainerSessionGate() {}
    public static synchronized boolean start(int id) {
        if (maintenance || edits.contains(id) || sessions.contains(id)) return false;
        sessions.add(id); return true;
    }
    public static synchronized void stop(int id) { sessions.remove(id); }
    public static synchronized boolean edit(int id) {
        if (maintenance || sessions.contains(id) || edits.contains(id)) return false;
        edits.add(id); return true;
    }
    public static synchronized void edited(int id) { edits.remove(id); }
    public static synchronized boolean hasActiveSessions() { return !sessions.isEmpty(); }
    public static synchronized boolean anyBusy() { return maintenance || !sessions.isEmpty() || !edits.isEmpty(); }
    public static synchronized boolean maintain() {
        if (anyBusy() || transfers > 0) return false;
        maintenance = true; return true;
    }
    public static synchronized boolean transfer() {
        if (maintenance) return false;
        transfers++; return true;
    }
    public static synchronized void transferred() { if (transfers > 0) transfers--; }
    public static synchronized void maintained() { maintenance = false; }
}
