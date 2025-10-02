package org.example;

import java.net.http.*;
import java.net.URI;
import java.util.concurrent.*;

public class Client {
    public static void main(String[] args) throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        ExecutorService exec = Executors.newFixedThreadPool(10);
        for (int i=0;i<50;i++) {
            exec.submit(() -> {
                try {
                    var r = c.send(HttpRequest.newBuilder(URI.create("http://localhost:8080/"))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
                    System.out.println(r.body());
                } catch (Exception e) { e.printStackTrace(); }
            });
        }
        exec.shutdown();
    }
}

