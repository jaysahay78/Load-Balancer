package alg;


import org.example.LoadBalancer.RequestContext;
import org.example.LoadBalancer.Upstream;
import java.util.List;

public interface BalancingStrategy {
    Upstream select(List<Upstream> pool, RequestContext ctx);
    default void onSuccess(Upstream u, long micros) {}
    default void onFailure(Upstream u, Throwable t) {}
}

