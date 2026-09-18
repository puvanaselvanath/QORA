package qora;

import com.sun.net.httpserver.*;
import qora.Models.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class Main {

    static Store store;
    static final Path PUBLIC_DIR = Paths.get("public");

    public static void main(String[] args) throws Exception {
        store = Store.load();
        // periodic autosave so a crash doesn't lose too much demo data
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "autosave");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(store::save, 10, 10, TimeUnit.SECONDS);

        // Try 8080, then a few alternatives, so "port already in use" doesn't
        // become a dead end for someone who has another app on 8080.
        HttpServer server = null;
        int port = 0;
        for (int candidate : new int[]{8080, 8081, 8090, 3000, 0}) {
            try {
                server = HttpServer.create(new InetSocketAddress(candidate), 0);
                port = server.getAddress().getPort();
                break;
            } catch (IOException e) {
                System.out.println("[QORA] Port " + candidate + " is busy, trying another...");
            }
        }
        if (server == null) {
            System.out.println("Could not start QORA: no available port.");
            System.out.println("Close other running copies of QORA and try again.");
            pause();
            return;
        }

        server.createContext("/api/", Main::handleApi);
        server.createContext("/", Main::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        String url = "http://localhost:" + port;
        System.out.println();
        System.out.println("=================================================");
        System.out.println("  QORA is running.");
        System.out.println();
        System.out.println("  Open this in your browser:");
        System.out.println("      " + url);
        System.out.println();
        System.out.println("  Keep this window open while using QORA.");
        System.out.println("  Close it (or press Ctrl+C) to stop.");
        System.out.println("=================================================");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(store::save));
        openBrowser(url);
    }

    /** Best-effort: pop the default browser open so there's nothing to type. */
    static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                System.out.println("[QORA] Opened your browser.");
                return;
            }
        } catch (Exception ignored) {
        }
        // Fall back to OS-level commands (works where AWT Desktop isn't available)
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd = null;
        if (os.contains("win")) cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        else if (os.contains("mac")) cmd = new String[]{"open", url};
        else cmd = new String[]{"xdg-open", url};
        try {
            new ProcessBuilder(cmd).start();
            System.out.println("[QORA] Opened your browser.");
        } catch (Exception e) {
            System.out.println("[QORA] Please open " + url + " manually.");
        }
    }

    /** Keeps a double-clicked window open long enough to read an error. */
    static void pause() {
        System.out.println();
        System.out.println("Press Enter to close this window...");
        try { new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine(); }
        catch (IOException ignored) { }
    }

    // ---------------- Static file serving ----------------

    static void handleStatic(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) path = "/index.html";
            // block path traversal before we look anywhere
            if (path.contains("..")) path = "/index.html";

            byte[] bytes = readStatic(path);
            if (bytes == null) bytes = readStatic("/index.html"); // SPA fallback
            if (bytes == null) {
                sendJson(ex, 404, Json.obj("error", "Web files not found."));
                return;
            }
            ex.getResponseHeaders().add("Content-Type", contentType(path));
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            sendJson(ex, 500, Json.obj("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * Reads a web file from inside the jar first (so the packaged single-file
     * build works from any directory), falling back to the public/ folder on
     * disk when running from compiled classes during development.
     */
    static byte[] readStatic(String path) {
        try (InputStream in = Main.class.getResourceAsStream("/public" + path)) {
            if (in != null) return in.readAllBytes();
        } catch (IOException ignored) {
        }
        try {
            Path file = PUBLIC_DIR.resolve(path.substring(1)).normalize();
            if (file.startsWith(PUBLIC_DIR) && Files.exists(file) && !Files.isDirectory(file)) {
                return Files.readAllBytes(file);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    // ---------------- API dispatch ----------------

    static void handleApi(HttpExchange ex) {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        try {
            // CORS not strictly needed (same-origin), but harmless for local testing tools
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            if (method.equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }

            String[] parts = path.split("/"); // "", "api", ...
            Api.Ctx ctx = new Api.Ctx(store, ex);

            if (method.equals("POST") && path.equals("/api/auth/register")) { Api.register(ctx); return; }
            if (method.equals("POST") && path.equals("/api/auth/login")) { Api.login(ctx); return; }
            if (method.equals("GET") && path.equals("/api/me")) { Api.me(ctx); return; }

            if (method.equals("GET") && path.equals("/api/businesses")) { Api.listBusinesses(ctx); return; }
            if (method.equals("POST") && path.equals("/api/businesses")) { Api.createBusiness(ctx); return; }
            if (method.equals("GET") && path.equals("/api/my-business")) { Api.myBusiness(ctx); return; }

            if (parts.length == 5 && parts[2].equals("businesses") && parts[4].equals("queues")) {
                String businessId = parts[3];
                if (method.equals("GET")) { Api.listQueues(ctx, businessId); return; }
                if (method.equals("POST")) { Api.createQueue(ctx, businessId); return; }
            }

            if (parts.length >= 4 && parts[2].equals("queues")) {
                String queueId = parts[3];
                if (parts.length == 4 && method.equals("GET")) { Api.getQueue(ctx, queueId); return; }
                if (parts.length == 5 && parts[4].equals("join") && method.equals("POST")) { Api.joinQueue(ctx, queueId); return; }
                if (parts.length == 5 && parts[4].equals("my-entry") && method.equals("GET")) { Api.myEntry(ctx, queueId); return; }
                if (parts.length == 5 && parts[4].equals("entries") && method.equals("GET")) { Api.listEntries(ctx, queueId); return; }
                if (parts.length == 5 && parts[4].equals("call-next") && method.equals("POST")) { Api.callNext(ctx, queueId); return; }
            }

            if (parts.length == 5 && parts[2].equals("entries")) {
                String entryId = parts[3];
                String action = parts[4];
                if (method.equals("POST") && action.equals("serve")) { Api.markServed(ctx, entryId); return; }
                if (method.equals("POST") && action.equals("no-show")) { Api.markNoShow(ctx, entryId); return; }
                if (method.equals("POST") && action.equals("cancel")) { Api.cancelEntry(ctx, entryId); return; }
            }

            sendJson(ex, 404, Json.obj("error", "Not found"));
        } catch (Api.ApiError ae) {
            sendJson(ex, ae.status, Json.obj("error", ae.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Json.obj("error", "Internal error: " + e.getMessage()));
        }
    }

    static void sendJson(HttpExchange ex, int status, Object body) {
        try {
            byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (IOException ignored) {
        }
    }
}
