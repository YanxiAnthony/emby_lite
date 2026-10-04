package com.example.embylite;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Small loopback HTTP fixture; no production server or Android runtime is involved. */
final class TestHttpServer implements AutoCloseable {
    interface Handler {
        Response respond(String method, URI uri, String body) throws Exception;
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final ServerSocket server = new ServerSocket();
    private final Thread worker;

    TestHttpServer(Handler handler) throws Exception {
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        worker = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    String[] request = input.readLine().split(" ");
                    int length = 0;
                    String header;
                    while (!(header = input.readLine()).isEmpty()) {
                        if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                        }
                    }
                    char[] body = new char[length];
                    int read = 0;
                    while (read < length) {
                        int count = input.read(body, read, length - read);
                        if (count < 0) break;
                        read += count;
                    }
                    Response response = handler.respond(request[0], URI.create(request[1]),
                            new String(body, 0, read));
                    byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 " + response.status + " Test\r\n"
                            + "Content-Type: application/json\r\nConnection: close\r\n"
                            + "Content-Length: " + bytes.length + "\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(bytes);
                    socket.getOutputStream().flush();
                } catch (Exception error) {
                    if (!server.isClosed()) throw new RuntimeException(error);
                }
            }
        }, "recent-sync-test-server");
        worker.setDaemon(true);
        worker.start();
    }

    String url() { return "http://127.0.0.1:" + server.getLocalPort(); }

    @Override public void close() throws Exception {
        server.close();
        worker.join(5000);
    }
}
