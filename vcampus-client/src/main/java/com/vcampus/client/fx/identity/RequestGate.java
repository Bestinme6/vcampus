package com.vcampus.client.fx.identity;

public final class RequestGate {
    private long generation;
    private boolean closed;

    public synchronized long next() { return ++generation; }
    public synchronized boolean accepts(long value) { return !closed && value == generation; }
    public synchronized void close() { closed = true; generation++; }
}
