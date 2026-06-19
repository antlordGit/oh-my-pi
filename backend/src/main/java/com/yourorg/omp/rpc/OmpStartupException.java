package com.yourorg.omp.rpc;

public class OmpStartupException extends RuntimeException {
    public OmpStartupException(String message) { super(message); }
    public OmpStartupException(String message, Throwable cause) { super(message, cause); }
}