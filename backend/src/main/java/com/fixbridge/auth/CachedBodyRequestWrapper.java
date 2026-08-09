package com.fixbridge.auth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Buffers a request body so it can be read twice: once by the rate limiter (to see which account is
 * being targeted) and again by the controller. A servlet body is a one-shot stream, so without this
 * the limiter would consume it and the controller would receive nothing.
 *
 * <p>Only ever wraps small credential payloads, so holding the body in memory is not a concern.
 */
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper
        implements AuthRateLimitFilter.CachedBodyRequest {

    private final byte[] cached;

    public CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.cached = request.getInputStream().readAllBytes();
    }

    @Override
    public String body() {
        return new String(cached, StandardCharsets.UTF_8);
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(cached);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Synchronous reads only — this wrapper is never used in async mode.
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
