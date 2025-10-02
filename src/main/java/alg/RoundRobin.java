package alg;


import org.example.LoadBalancer.Upstream;
import org.example.LoadBalancer.RequestContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobin implements BalancingStrategy {
    private final AtomicInteger idx = new AtomicInteger(0);
    @Override public Upstream select(List<Upstream> pool, RequestContext ctx) {
        if (pool.isEmpty()) throw new IllegalStateException("No upstreams");
        int i = Math.floorMod(idx.getAndIncrement(), pool.size());
        return pool.get(i);
    }
}

