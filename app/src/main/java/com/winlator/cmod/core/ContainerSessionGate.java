package com.winlator.cmod.core;

/** Serializes prefix mutations with Wine startup. Leases last only for the operation/session. */
public final class ContainerSessionGate {
    private static final java.util.Set<Integer> sessions = new java.util.HashSet<>();
    private static final java.util.Set<Integer> edits = new java.util.HashSet<>();
    private ContainerSessionGate() {}
    public static synchronized boolean start(int id) {
        if (edits.contains(id) || sessions.contains(id)) return false;
        sessions.add(id); return true;
    }
    public static synchronized void stop(int id) { sessions.remove(id); }
    public static synchronized boolean edit(int id) {
        if (sessions.contains(id) || edits.contains(id)) return false;
        edits.add(id); return true;
    }
    public static synchronized void edited(int id) { edits.remove(id); }
    public static synchronized boolean hasActiveSessions() { return !sessions.isEmpty(); }
    public static synchronized boolean anyBusy() { return !sessions.isEmpty() || !edits.isEmpty(); }
}
