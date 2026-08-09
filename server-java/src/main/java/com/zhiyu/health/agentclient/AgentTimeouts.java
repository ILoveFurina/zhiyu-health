package com.zhiyu.health.agentclient;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

final class AgentTimeouts {
    private AgentTimeouts() {}

    static boolean causedByTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
