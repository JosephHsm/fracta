package com.fracta.ai;

/**
 * AI 서비스에 도달할 수 없다. 본 서비스의 다른 기능은 계속 동작해야 하므로
 * 호출부는 이 예외를 잡아 "AI 기능만 비활성"으로 처리한다.
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
