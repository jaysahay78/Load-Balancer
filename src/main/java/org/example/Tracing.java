package org.example;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

public final class Tracing {
    public static final Tracer TRACER;
    static {
        var exporter = OtlpGrpcSpanExporter.builder().build();
        var provider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build()).build();
        TRACER = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
                .getTracer("org.example");
    }
}
