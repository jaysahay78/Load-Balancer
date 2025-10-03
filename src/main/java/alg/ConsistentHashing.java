package alg;

import org.example.LoadBalancer.*;
import com.google.common.hash.Hashing;
import java.util.*;

public class ConsistentHashing implements BalancingStrategy {
    private final NavigableMap<Long, Upstream> ring = new TreeMap<>();
    public synchronized void rebuild(List<Upstream> pool) {
        ring.clear();
        for (Upstream u : pool) {
            for (int v=0; v<u.weight*100; v++) {
                long h = Hashing.murmur3_128().hashUnencodedChars(u.id+"-"+v).asLong();
                ring.put(h,u);
            }
        }
    }
    @Override
    public Upstream select(List<Upstream> pool, RequestContext ctx) {
        if (ring.isEmpty()) rebuild(pool);
        String key = ctx.getStickyKey();
        long h = Hashing.murmur3_128().hashUnencodedChars(key).asLong();
        var e = ring.ceilingEntry(h);
        return (e!=null?e.getValue():ring.firstEntry().getValue());
    }
}
