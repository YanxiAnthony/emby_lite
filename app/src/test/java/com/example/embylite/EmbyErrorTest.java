package com.example.embylite;

import org.junit.Test;
import static org.junit.Assert.*;

public class EmbyErrorTest {
    @Test public void errorDoesNotExposeRawServerResponse() throws Exception {
        try (TestHttpServer server = new TestHttpServer((method, uri, body) ->
                new TestHttpServer.Response(403, "private-server-detail"))) {
            EmbyClient client = new EmbyClient(server.url(), "test", "test");
            try {
                client.loadMovies("user", false);
                fail("Expected permission error");
            } catch (EmbyClient.HttpException error) {
                assertEquals(403, error.statusCode);
                assertEquals("GET", error.method);
                assertFalse(error.getMessage().contains("private-server-detail"));
            }
        }
    }
}
