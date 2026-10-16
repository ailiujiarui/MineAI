package com.dwinovo.numen.api.agent.loop;

import com.dwinovo.numen.api.agent.http.LlmHttpException;
import com.dwinovo.numen.api.agent.http.LlmStreamException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 一次模型调用抛出来的错归成哪个原因码。 */
class CauseTest {

    @Test
    void aStreamThatFailedAfterItStartedCarriesTheVendorsWords() {
        Cause cause = Cause.fromError(new CompletionException(new LlmStreamException("server_error: overloaded")));

        assertEquals(Cause.of(Cause.STREAM_FAILED, "server_error: overloaded"), cause);
        assertEquals("回应中途出错:server_error: overloaded", cause.forModel());
    }

    @Test
    void anHttpRefusalStillSortsByStatus() {
        Cause cause = Cause.fromError(new CompletionException(new LlmHttpException(429, "{}", "api.openai.com")));

        assertEquals(Cause.RATE_LIMITED, cause.code());
    }
}
