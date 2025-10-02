package org.example;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

public final class Metrics {
    public static final PrometheusMeterRegistry REG =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    public static final Counter REQ = Counter.builder("lb_requests_total")
            .description("Total requests").register(REG);

    public static final Timer UPSTREAM_LAT = Timer.builder("lb_upstream_latency")
            .publishPercentileHistogram().register(REG);
}
