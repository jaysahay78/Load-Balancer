package org.example;

public class Worker {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9001;
        var srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port),0);
        srv.createContext("/", h -> {
            byte[] body = ("hello from " + port).getBytes();
            h.sendResponseHeaders(200, body.length);
            h.getResponseBody().write(body);
            h.close();
        });
        srv.start();
        System.out.println("Worker listening on " + port);
    }
}

