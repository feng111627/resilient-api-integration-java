package com.github.feng111627.resilientapiintegrationjava.error;

/** 调用层统一异常：对外只暴露该类型，不透出底层 HTTP/序列化异常。 */
public class UpstreamException extends RuntimeException {

    private final ErrorKind kind;
    private final Integer upstreamStatus;
    private final String requestId;

    public UpstreamException(ErrorKind kind, String message) {
        this(kind, message, null, null, null);
    }

    public UpstreamException(ErrorKind kind, String message, Integer upstreamStatus,
                             String requestId, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
        this.requestId = requestId;
    }

    public ErrorKind kind() {
        return kind;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    public String requestId() {
        return requestId;
    }
}
