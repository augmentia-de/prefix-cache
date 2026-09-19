package dev.prefix.config;

public class RequestContext {
        public static ThreadLocal<String> currentRequestId = new ThreadLocal<>();
    }
