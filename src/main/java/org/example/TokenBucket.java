package org.example;

public final class TokenBucket {
    private final long capacity, refillPerSec;
    private double tokens;
    private long last = System.nanoTime();
    public TokenBucket(long capacity, long refillPerSec) {
        this.capacity = capacity; this.refillPerSec = refillPerSec; this.tokens = capacity;
    }
    public synchronized boolean tryConsume(int n) {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - last) / 1e9 * refillPerSec);
        last = now;
        if (tokens >= n) { tokens -= n; return true; }
        return false;
    }
}

