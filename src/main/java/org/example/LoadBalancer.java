package org.example;



import alg.*;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class LoadBalancer {
    private static final Logger JUL = Logger.getLogger("lb");

    public static void main(String[] args) throws Exception {
        var cli = parseArgs(args);
        var configPath = cli.getOrDefault("config", "config\\\\application.yaml");
        var cfg = loadConfig(Path.of(configPath));
        var lb = new LoadBalancer(cfg);
        lb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(lb::shutdown, "lb-shutdown"));
    }

    // ------------ instance state ------------
    private final AppConfig cfg;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Selector selector;
    private ServerSocketChannel server;
    private HttpServer admin;
    private final PrometheusMeterRegistry prom = Metrics.REG;

    // concurrency (virtual threads)
    private Semaphore gate;
    private ExecutorService vexec;

    // rate limit
    private TokenBucket bucket;

    // strategy & pool
    private final List<Upstream> pool = new CopyOnWriteArrayList<>();
    private BalancingStrategy strategy;

    public LoadBalancer(AppConfig cfg) { this.cfg = cfg; }

    public void start() throws Exception {
        for (var u : cfg.upstreams) pool.add(new Upstream(u.id, u.host, u.port, u.weight));

        String strategyName = cfg.strategy.toUpperCase(Locale.ROOT);
        if ("LEAST_CONNECTIONS".equals(strategyName)) {
            strategy = new LeastConnections();
        } else {
            strategy = new RoundRobin();
        }

        bucket = new TokenBucket(cfg.rateLimit.burst, cfg.rateLimit.rps);

        gate = new Semaphore(cfg.server.maxConcurrent);
        vexec = Executors.newVirtualThreadPerTaskExecutor();

        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.configureBlocking(false);
        var saddr = new InetSocketAddress(cfg.server.listenPort);
        server.bind(saddr);
        server.register(selector, SelectionKey.OP_ACCEPT);
        JUL.info(() -> "LB listening on " + saddr);

        startAdmin();

        running.set(true);
        eventLoop();
    }

    private void eventLoop() throws IOException {
        while (running.get()) {
            selector.select(250);
            var iter = selector.selectedKeys().iterator();
            while (iter.hasNext()) {
                var key = iter.next(); iter.remove();
                try {
                    if (!key.isValid()) continue;
                    if (key.isAcceptable()) accept(key);
                    else if (key.isReadable()) {
                        var ctx = (ConnCtx) key.attachment();
                        if (ctx == null) { key.cancel(); continue; }
                        key.cancel(); // hand over to VT

                        if (!gate.tryAcquire()) { writeAndClose(ctx.client, "HTTP/1.1 503 Service Unavailable\r\n\r\n"); closeQuiet(ctx); continue; }

                        vexec.submit(() -> {
                            try { ctx.client.configureBlocking(true); handleClientBlocking(ctx); }
                            catch (Exception e) { try { writeAndClose(ctx.client, "HTTP/1.1 500 Internal Server Error\r\n\r\n"); } catch (Exception ignore) {} }
                            finally { closeQuiet(ctx); gate.release(); }
                        });
                    }
                } catch (Exception e) { JUL.warning("loop error: " + e.getMessage()); }
            }
        }
    }

    private void handleClientBlocking(ConnCtx ctx) throws IOException {
        var reqBuf = ByteBuffer.allocate(64 * 1024);
        int read = ctx.client.read(reqBuf);
        if (read <= 0) return;
        reqBuf.flip();
        byte[] reqBytes = new byte[reqBuf.remaining()];
        reqBuf.get(reqBytes);
        String reqText = new String(reqBytes, StandardCharsets.US_ASCII);

        if (reqText.length() > cfg.server.maxHeaderBytes) { writeAndClose(ctx.client, "HTTP/1.1 431 Request Header Fields Too Large\r\n\r\n"); return; }
        if (!bucket.tryConsume(1)) { writeAndClose(ctx.client, "HTTP/1.1 429 Too Many Requests\r\n\r\n"); return; }

        int rn = reqText.indexOf("\r\n");
        if (rn < 0) { writeAndClose(ctx.client, "HTTP/1.1 400 Bad Request\r\n\r\n"); return; }
        String[] parts = reqText.substring(0, rn).split(" ");
        if (parts.length < 3) { writeAndClose(ctx.client, "HTTP/1.1 400 Bad Request\r\n\r\n"); return; }
        String method = parts[0], path = parts[1];

        String stickyKey = extractStickyKey(reqText, cfg.sticky);
        InetAddress clientAddress = ctx.client.socket() != null ? ctx.client.socket().getInetAddress() : null;
        var rc = new RequestContext(clientAddress, stickyKey, method, path, reqText);

        Upstream upstream;
        try { upstream = strategy.select(pool, rc); }
        catch (Exception e) { writeAndClose(ctx.client, "HTTP/1.1 503 Service Unavailable\r\n\r\n"); return; }

        if (!upstream.cb.allow()) { writeAndClose(ctx.client, "HTTP/1.1 503 Service Unavailable\r\n\r\n"); return; }

        long start = System.nanoTime();
        upstream.inFlight.incrementAndGet();
        try (var up = SocketChannel.open()) {
            up.configureBlocking(true);
            up.connect(new InetSocketAddress(upstream.host, upstream.port));
            up.write(ByteBuffer.wrap(reqBytes));

            var resp = ByteBuffer.allocate(64 * 1024);
            int n;
            while ((n = up.read(resp)) >= 0) {
                resp.flip();
                while (resp.hasRemaining()) ctx.client.write(resp);
                resp.clear();
                if (n == 0) break;
            }

            long micros = (System.nanoTime() - start) / 1_000;
            Metrics.REQ.increment();
            Metrics.UPSTREAM_LAT.record(micros, TimeUnit.MICROSECONDS);

            upstream.cb.success();
            strategy.onSuccess(upstream, micros);
        } catch (Exception ex) {
            upstream.cb.failure();
            strategy.onFailure(upstream, ex);
            writeAndClose(ctx.client, "HTTP/1.1 502 Bad Gateway\r\n\r\n");
        } finally {
            upstream.inFlight.decrementAndGet();
        }
    }

    private void accept(SelectionKey key) throws IOException {
        var ssc = (ServerSocketChannel) key.channel();
        var client = ssc.accept();
        if (client == null) return;
        client.configureBlocking(false);
        var ctx = new ConnCtx(client, System.nanoTime());
        client.register(selector, SelectionKey.OP_READ, ctx);
    }

    private static void writeAndClose(SocketChannel ch, String text) throws IOException {
        ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }
    private void closeQuiet(ConnCtx ctx) { try { ctx.client.close(); } catch (Exception ignored) {} }

    private void startAdmin() throws IOException {
        admin = HttpServer.create(new InetSocketAddress(cfg.server.adminPort), 0);
        admin.createContext("/metrics", h -> {
            var body = prom.scrape();
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            h.getResponseHeaders().add("Content-Type","text/plain; version=0.0.4");
            h.sendResponseHeaders(200, bytes.length);
            h.getResponseBody().write(bytes);
            h.close();
        });
        admin.createContext("/live", h -> { var b = "OK".getBytes(); h.sendResponseHeaders(200, b.length); h.getResponseBody().write(b); h.close(); });
        admin.createContext("/ready", h -> {
            var ok = !pool.isEmpty(); var b = (ok ? "READY":"NOT_READY").getBytes();
            h.sendResponseHeaders(ok?200:503, b.length); h.getResponseBody().write(b); h.close();
        });
        admin.start();
        JUL.info(() -> "Admin on :" + cfg.server.adminPort + " (/metrics /live /ready)");
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;
        JUL.info("Shutting down… draining connections");
        try { if (admin != null) admin.stop(0); } catch (Exception ignored) {}
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (selector != null) selector.close(); } catch (Exception ignored) {}
        if (vexec != null) { vexec.shutdown(); try { vexec.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} }
    }

    // ---------- helpers & config ----------
    private static Map<String, String> parseArgs(String[] args) {
        var map = new HashMap<String, String>();
        for (var a : args) if (a.startsWith("--")) {
            var eq = a.indexOf('='); if (eq > 2) map.put(a.substring(2, eq), a.substring(eq + 1)); else map.put(a.substring(2), "true");
        }
        return map;
    }
    private static AppConfig loadConfig(Path path) throws IOException {
        // 1) Try classpath (src/main/resources/application.yaml)
        try (InputStream in = ClassLoader.getSystemResourceAsStream("application.yaml")) {
            if (in != null) {
                var yaml = new Yaml(new Constructor(AppConfig.class, new LoaderOptions()));
                return yaml.load(in);
            }
        }
        // 2) Fallback to filesystem path
        try (InputStream in = java.nio.file.Files.newInputStream(path)) {
            var yaml = new Yaml(new Constructor(AppConfig.class, new LoaderOptions()));
            return yaml.load(in);
        }
    }

    private static String extractStickyKey(String rawReq, Sticky sticky) {
        if (sticky == null || sticky.key == null) return "";
        if ("ip".equalsIgnoreCase(sticky.key)) return "ip";
        for (var line : rawReq.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(sticky.key.toLowerCase(Locale.ROOT) + ":")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return "";
    }

    // ---------- config classes ----------
    public static class AppConfig {
        public Server server;
        public String strategy = "LEAST_CONNECTIONS";
        public Sticky sticky = new Sticky();
        public RateLimit rateLimit = new RateLimit();
        public List<UpstreamCfg> upstreams = new ArrayList<>();
    }
    public static class Server { public int listenPort=8080, adminPort=9000, maxHeaderBytes=16384, maxBodyBytes=10_485_760, idleTimeoutMs=15_000, maxConcurrent=1000; public String protocol="HTTP1"; }
    public static class Sticky { public String key="X-Session-Key"; }
    public static class RateLimit { public long rps=5000, burst=10_000; }
    public static class UpstreamCfg { public String id, host="127.0.0.1"; public int port, weight=5; }

    // ---------- runtime models ----------
    public static final class Upstream {
        public final String id, host; public final int port; public final int weight;
        private volatile boolean healthy = true;
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final SimpleCircuitBreaker cb = new SimpleCircuitBreaker();
        Upstream(String id, String host, int port, int weight) { this.id=id; this.host=host; this.port=port; this.weight=weight; }
        public boolean isHealthy() { return healthy && cb.allow(); }
        public int inFlight() { return inFlight.get(); }
    }
    public static final class SimpleCircuitBreaker {
        private enum State {CLOSED, OPEN, HALF_OPEN}
        private volatile State s = State.CLOSED;
        private volatile long openedAt = 0;
        boolean allow() {
            if (s == State.OPEN && (System.nanoTime() - openedAt) > TimeUnit.SECONDS.toNanos(30)) { s = State.HALF_OPEN; return true; }
            return s != State.OPEN;
        }
        void success() { s = State.CLOSED; }
        void failure() { if (s == State.HALF_OPEN || s == State.CLOSED) { s = State.OPEN; openedAt = System.nanoTime(); } }
    }
    public record RequestContext(InetAddress clientIp, String sticky, String method, String path, String raw) {
        public InetAddress clientIp() { return clientIp; }
        public String getStickyKey() { return (sticky != null && !sticky.isEmpty()) ? sticky : clientIp().getHostAddress(); }
        public Optional<String> header(String name) {
            var low = name.toLowerCase(Locale.ROOT)+":";
            for (var line : raw.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith(low)) return Optional.of(line.substring(line.indexOf(':')+1).trim());
            return Optional.empty();
        }
    }
    private record ConnCtx(SocketChannel client, long acceptedAtNanos) {}
}

