package com.fracta.openapi.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/** 응답 본문을 가로채 멱등성 저장에 쓴다. 실제 응답은 {@link #flushToClient()}로 내보낸다. */
public class CachedBodyResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintWriter writer = new PrintWriter(buffer, true, StandardCharsets.UTF_8);

    public CachedBodyResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    public String bodyAsString() {
        writer.flush();
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public void flushToClient() throws IOException {
        writer.flush();
        byte[] bytes = buffer.toByteArray();
        getResponse().setContentLength(bytes.length);
        getResponse().getOutputStream().write(bytes);
        getResponse().flushBuffer();
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(int b) {
                buffer.write(b);
            }
        };
    }

    @Override
    public PrintWriter getWriter() {
        return writer;
    }
}
