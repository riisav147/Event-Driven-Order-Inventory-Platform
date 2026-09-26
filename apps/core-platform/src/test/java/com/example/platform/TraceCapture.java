package com.example.platform;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.actuate.autoconfigure.tracing.SdkTracerProviderBuilderCustomizer;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

@TestConfiguration(proxyBeanMethods = false)
class TraceCapture {
    static final Queue<SpanData> SPANS = new ArrayBlockingQueue<>(10000);
    @Bean SdkTracerProviderBuilderCustomizer captureSpans() {
        return builder -> builder.addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> spans) {
                spans.forEach(SPANS::offer); return CompletableResultCode.ofSuccess();
            }
            public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }));
    }
}
