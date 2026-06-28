package com.minigenesys.apigateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Access log for the gateway. Spring Cloud Gateway does not log per-request by
 * default, so without this the gateway log only shows startup output. Logging at
 * INFO makes each request visible (and streamable in the diagnostics dashboard).
 * Ordered before {@link JwtAuthenticationFilter} (-1) so the final status is
 * captured even when auth rejects the request.
 */
@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod().name();
        String path = request.getURI().getRawPath();
        String query = request.getURI().getRawQuery();
        String fullPath = query == null ? path : path + "?" + query;
        long start = System.currentTimeMillis();

        return chain.filter(exchange).doFinally(signal -> {
            long elapsed = System.currentTimeMillis() - start;
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            int code = status == null ? 0 : status.value();
            log.info("{} {} -> {} ({} ms)", method, fullPath, code, elapsed);
        });
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
