package alg;

import org.example.LoadBalancer.Upstream;
import org.example.LoadBalancer.RequestContext;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class WeightedRoundRobin implements BalancingStrategy {
    private final AtomicInteger idx = new AtomicInteger();
    @Override public Upstream select(List<Upstream> pool, RequestContext ctx) {
        var list = new ArrayList<Upstream>();
        for (var u : pool) for (int i=0;i<u.weight;i++) list.add(u);
        if (list.isEmpty()) throw new IllegalStateException("No upstreams");
        int i = Math.floorMod(idx.getAndIncrement(), list.size());
        return list.get(i);
    }
}
