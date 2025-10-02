package alg;


import org.example.LoadBalancer.Upstream;
import org.example.LoadBalancer.RequestContext;
import java.util.List;

public class LeastConnections implements BalancingStrategy {
    @Override public Upstream select(List<Upstream> pool, RequestContext ctx) {
        return pool.stream().filter(Upstream::isHealthy)
                .min((a,b) -> Integer.compare(a.inFlight(), b.inFlight()))
                .orElseThrow(() -> new IllegalStateException("No healthy upstreams"));
    }
}
