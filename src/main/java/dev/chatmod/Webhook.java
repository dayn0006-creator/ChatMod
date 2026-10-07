package dev.chatmod;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class Webhook {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Webhook() {}

    public static void send(String url, JsonObject payload, byte[] png) {
        if (url == null || !url.startsWith("https://discord")) {
            ChatModClient.LOG.warn("Webhook не настроен: впиши ссылку в config/chatmoderator.json и сделай /chatmod reload");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String json = payload.toString();
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
                HttpRequest req;
                if (png == null) {
                    req = b.header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
                } else {
                    String boundary = "----chatmod" + System.nanoTime();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    write(out, "--" + boundary + "\r\n");
                    write(out, "Content-Disposition: form-data; name=\"payload_json\"\r\n");
                    write(out, "Content-Type: application/json\r\n\r\n");
                    write(out, json + "\r\n");
                    write(out, "--" + boundary + "\r\n");
                    write(out, "Content-Disposition: form-data; name=\"files[0]\"; filename=\"screenshot.png\"\r\n");
                    write(out, "Content-Type: image/png\r\n\r\n");
                    out.write(png);
                    write(out, "\r\n--" + boundary + "--\r\n");
                    req = b.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
                }
                HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() >= 300) {
                    ChatModClient.LOG.warn("Webhook вернул {}: {}", r.statusCode(), r.body());
                }
            } catch (Exception e) {
                ChatModClient.LOG.warn("Ошибка отправки в webhook", e);
            }
        });
    }

    private static void write(ByteArrayOutputStream out, String s) throws java.io.IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
