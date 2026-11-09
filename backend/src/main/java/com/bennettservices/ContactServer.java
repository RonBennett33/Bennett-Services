package com.bennettservices;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class ContactServer {
    private static final int MAX_FORM_BYTES = 64 * 1024;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ContactServer() {}

    public static void main(String[] args) throws IOException {
        String apiKey = requiredEnvironment("MAILGUN_API_KEY");
        String domain = requiredEnvironment("MAILGUN_DOMAIN");
        String recipient = requiredEnvironment("MAILGUN_TO");
        if (!domain.matches("[A-Za-z0-9.-]+")) {
            throw new IllegalArgumentException("MAILGUN_DOMAIN must be a Mailgun domain name.");
        }

        String sender = System.getenv().getOrDefault("MAILGUN_FROM", "Bennett Services <postmaster@" + domain + ">");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path webRoot = Path.of(System.getenv().getOrDefault("WEB_ROOT", ".")).toRealPath();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/contact", exchange -> handleContact(exchange, apiKey, domain, sender, recipient));
        server.createContext("/", exchange -> serveStatic(exchange, webRoot));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Bennett Services is listening on port " + port);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set the " + name + " environment variable.");
        }
        return value;
    }

    private static void handleContact(
            HttpExchange exchange, String apiKey, String domain, String sender, String recipient) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "This method is not allowed.");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            sendJson(exchange, 415, "Please submit the contact form.");
            return;
        }

        byte[] body = exchange.getRequestBody().readNBytes(MAX_FORM_BYTES + 1);
        if (body.length > MAX_FORM_BYTES) {
            sendJson(exchange, 413, "Your message is too long.");
            return;
        }
        Map<String, String> fields;
        try {
            fields = parseForm(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, "Please check the form and try again.");
            return;
        }

        String name = fields.getOrDefault("name", "").strip();
        String email = fields.getOrDefault("email", "").strip();
        String business = fields.getOrDefault("business", "").strip();
        String message = fields.getOrDefault("message", "").strip();
        if (name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)
                || !EMAIL.matcher(email).matches()
                || email.length() > 254 || business.length() > 120 || message.isEmpty() || message.length() > 3000) {
            sendJson(exchange, 400, "Please enter your name, a valid email address, and a message.");
            return;
        }

        String text = "Name: " + name + "\nEmail: " + email + "\nBusiness: " + business + "\n\n" + message;
        String form = "from=" + encode(sender)
                + "&to=" + encode(recipient)
                + "&subject=" + encode("Website enquiry from " + name)
                + "&text=" + encode(text)
                + "&h%3AReply-To=" + encode(email);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mailgun.net/v3/" + domain + "/messages"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("api:" + apiKey).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("Mailgun request failed with status " + response.statusCode());
                sendJson(exchange, 502, "We couldn’t send your message right now. Please try again later.");
                return;
            }
            sendJson(exchange, 200, "Thanks for reaching out. We’ll be in touch soon.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 502, "We couldn’t send your message right now. Please try again later.");
        } catch (IOException exception) {
            sendJson(exchange, 502, "We couldn’t send your message right now. Please try again later.");
        }
    }

    private static Map<String, String> parseForm(String form) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : form.split("&")) {
            int separator = pair.indexOf('=');
            String key = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(separator < 0 ? "" : pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (fields.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate form field.");
            }
        }
        return fields;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void serveStatic(HttpExchange exchange, Path webRoot) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String requestedPath = exchange.getRequestURI().getPath();
        if (requestedPath.equals("/")) {
            requestedPath = "/index.html";
        }
        if (!requestedPath.equals("/index.html") && !requestedPath.equals("/styles.css")
                && !requestedPath.equals("/app.js")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        Path file = webRoot.resolve(requestedPath.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        file = file.toRealPath();
        if (!file.startsWith(webRoot)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        String type = Files.probeContentType(file);
        exchange.getResponseHeaders().set("Content-Type", type == null ? "application/octet-stream" : type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            byte[] content = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        }
        exchange.close();
    }

    private static void sendJson(HttpExchange exchange, int status, String message) throws IOException {
        String json = "{\"message\":\"" + message + "\"}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
