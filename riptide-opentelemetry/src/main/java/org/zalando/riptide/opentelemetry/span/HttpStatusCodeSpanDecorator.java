package org.zalando.riptide.opentelemetry.span;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.semconv.incubating.HttpIncubatingAttributes;
import org.springframework.http.client.ClientHttpResponse;
import org.zalando.riptide.HttpResponseException;
import org.zalando.riptide.RequestArguments;

import java.io.IOException;

public class HttpStatusCodeSpanDecorator implements SpanDecorator {

    @Override
    public void onError(Span span, RequestArguments arguments, Throwable error) {
        if (error instanceof HttpResponseException) {
            setStatusCode(span, ((HttpResponseException) error).getRawStatusCode());
        }
    }

    @Override
    public void onResponse(Span span, RequestArguments arguments,
                           ClientHttpResponse response) throws IOException {
        setStatusCode(span, response.getStatusCode().value());
    }

    private void setStatusCode(Span span, int statusCode) {
        span.setAttribute(HttpIncubatingAttributes.HTTP_STATUS_CODE, statusCode);
    }
}
