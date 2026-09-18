package qora;

import com.sun.net.httpserver.HttpExchange;
import qora.Models.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Api {

    public static class ApiError extends RuntimeException {
        public final int status;
        public ApiError(int status, String message) { super(message); this.status = status; }
    }

    public static class Ctx {
        final Store store;
        final HttpExchange ex;
        Map<String, Object> body;

        Ctx(Store store, HttpExchange ex) { this.store = store; this.ex = ex; }

        Map<String, Object> body() {
            if (body == null) {
                try {
                    byte[] raw = ex.getRequestBody().readAllBytes();
                    String s = new String(raw, StandardCharsets.UTF_8);
                    body = s.isBlank() ? new LinkedHashMap<>() : Json.parseObject(s);
                } catch (Exception e) {
                    body = new LinkedHashMap<>();
                }
            }
            return body;
        }

        String str(String key) {
            Object v = body().get(key);
            return v == null ? null : v.toString().trim();
        }

        User requireUser() {
            String header = ex.getRequestHeaders().getFirst("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new ApiError(401, "Missing or invalid Authorization header. Log in first.");
            }
            String token = header.substring(7);
            String userId = store.tokenToUserId.get(token);
            if (userId == null) throw new ApiError(401, "Session expired or invalid. Please log in again.");
            User u = store.usersById.get(userId);
            if (u == null) throw new ApiError(401, "User no longer exists.");
            return u;
        }
    }

    // ---------------- Auth ----------------

    public static void register(Ctx ctx) {
        String name = ctx.str("name");
        String email = ctx.str("email");
        String phone = ctx.str("phone");
        String password = ctx.str("password");
        String roleStr = ctx.str("role");

        if (name == null || name.isEmpty()) throw new ApiError(400, "Name is required.");
        if ((email == null || email.isEmpty()) && (phone == null || phone.isEmpty())) {
            throw new ApiError(400, "Provide an email or a mobile number.");
        }
        if (password == null || password.length() < 6) {
            throw new ApiError(400, "Password must be at least 6 characters.");
        }
        Role role = Role.CUSTOMER;
        if ("BUSINESS_OWNER".equalsIgnoreCase(roleStr)) role = Role.BUSINESS_OWNER;

        if (email != null && !email.isEmpty() && ctx.store.emailToUserId.containsKey(email.toLowerCase())) {
            throw new ApiError(409, "An account with that email already exists.");
        }
        if (phone != null && !phone.isEmpty() && ctx.store.phoneToUserId.containsKey(phone)) {
            throw new ApiError(409, "An account with that mobile number already exists.");
        }

        User u = new User();
        u.id = Store.newId();
        u.name = name;
        u.email = (email == null || email.isEmpty()) ? null : email.toLowerCase();
        u.phone = (phone == null || phone.isEmpty()) ? null : phone;
        u.salt = AuthUtil.newSalt();
        u.passwordHash = AuthUtil.hash(password, u.salt);
        u.role = role;
        u.createdAt = System.currentTimeMillis();

        ctx.store.usersById.put(u.id, u);
        if (u.email != null) ctx.store.emailToUserId.put(u.email, u.id);
        if (u.phone != null) ctx.store.phoneToUserId.put(u.phone, u.id);
        ctx.store.save();

        String token = AuthUtil.newToken();
        ctx.store.tokenToUserId.put(token, u.id);

        Main.sendJson(ctx.ex, 201, Json.obj("token", token, "user", u.toPublic()));
    }

    public static void login(Ctx ctx) {
        String identifier = ctx.str("identifier"); // email or phone
        String password = ctx.str("password");
        if (identifier == null || password == null) {
            throw new ApiError(400, "Email/mobile and password are required.");
        }
        String userId = ctx.store.emailToUserId.get(identifier.toLowerCase());
        if (userId == null) userId = ctx.store.phoneToUserId.get(identifier);
        if (userId == null) throw new ApiError(401, "No account found with that email or mobile number.");

        User u = ctx.store.usersById.get(userId);
        if (u == null || !AuthUtil.matches(password, u.salt, u.passwordHash)) {
            throw new ApiError(401, "Incorrect password.");
        }
        String token = AuthUtil.newToken();
        ctx.store.tokenToUserId.put(token, u.id);
        Main.sendJson(ctx.ex, 200, Json.obj("token", token, "user", u.toPublic()));
    }

    public static void me(Ctx ctx) {
        User u = ctx.requireUser();
        Main.sendJson(ctx.ex, 200, u.toPublic());
    }

    // ---------------- Businesses ----------------

    public static void listBusinesses(Ctx ctx) {
        List<Object> out = new ArrayList<>();
        for (Business b : ctx.store.allBusinesses()) out.add(b.toPublic());
        Main.sendJson(ctx.ex, 200, out);
    }

    public static void myBusiness(Ctx ctx) {
        User u = ctx.requireUser();
        for (Business b : ctx.store.allBusinesses()) {
            if (b.ownerId.equals(u.id)) { Main.sendJson(ctx.ex, 200, b.toPublic()); return; }
        }
        Main.sendJson(ctx.ex, 200, Json.obj("none", true));
    }

    public static void createBusiness(Ctx ctx) {
        User u = ctx.requireUser();
        if (u.role != Role.BUSINESS_OWNER) throw new ApiError(403, "Only business accounts can create a business.");
        String name = ctx.str("name");
        String category = ctx.str("category");
        if (name == null || name.isEmpty()) throw new ApiError(400, "Business name is required.");

        for (Business existing : ctx.store.allBusinesses()) {
            if (existing.ownerId.equals(u.id)) throw new ApiError(409, "You already have a business registered.");
        }

        Business b = new Business();
        b.id = Store.newId();
        b.ownerId = u.id;
        b.name = name;
        b.category = (category == null || category.isEmpty()) ? "General" : category;
        String avgStr = ctx.str("avgServiceMinutes");
        try {
            // accept "8", 8, or 8.0 — Json parses bare numbers as Double
            b.avgServiceMinutes = (avgStr == null || avgStr.isEmpty())
                ? 5
                : Math.max(1, (int) Math.round(Double.parseDouble(avgStr)));
        } catch (NumberFormatException e) {
            b.avgServiceMinutes = 5;
        }
        b.createdAt = System.currentTimeMillis();
        ctx.store.businesses.put(b.id, b);
        ctx.store.save();
        Main.sendJson(ctx.ex, 201, b.toPublic());
    }

    // ---------------- Queues ----------------

    public static void listQueues(Ctx ctx, String businessId) {
        Business b = ctx.store.businesses.get(businessId);
        if (b == null) throw new ApiError(404, "Business not found.");
        List<Object> out = new ArrayList<>();
        for (WaitQueue q : ctx.store.queuesForBusiness(businessId)) {
            out.add(queueSummary(ctx.store, q));
        }
        Main.sendJson(ctx.ex, 200, out);
    }

    public static void createQueue(Ctx ctx, String businessId) {
        User u = ctx.requireUser();
        Business b = ctx.store.businesses.get(businessId);
        if (b == null) throw new ApiError(404, "Business not found.");
        if (!b.ownerId.equals(u.id)) throw new ApiError(403, "You don't own this business.");
        String name = ctx.str("name");
        if (name == null || name.isEmpty()) throw new ApiError(400, "Queue name is required.");

        WaitQueue q = new WaitQueue();
        q.id = Store.newId();
        q.businessId = businessId;
        q.name = name;
        q.createdAt = System.currentTimeMillis();
        ctx.store.queues.put(q.id, q);
        ctx.store.save();
        Main.sendJson(ctx.ex, 201, queueSummary(ctx.store, q));
    }

    public static void getQueue(Ctx ctx, String queueId) {
        WaitQueue q = requireQueue(ctx, queueId);
        Main.sendJson(ctx.ex, 200, queueSummary(ctx.store, q));
    }

    static Map<String, Object> queueSummary(Store store, WaitQueue q) {
        Business b = store.businesses.get(q.businessId);
        List<QueueEntry> waiting = store.waitingEntries(q.id);
        return Json.obj(
            "id", q.id, "businessId", q.businessId,
            "businessName", b == null ? "" : b.name,
            "name", q.name, "active", q.active,
            "waitingCount", waiting.size(),
            "avgServiceMinutes", b == null ? 5 : b.avgServiceMinutes
        );
    }

    // ---------------- WaitQueue entries ----------------

    public static void joinQueue(Ctx ctx, String queueId) {
        User u = ctx.requireUser();
        WaitQueue q = requireQueue(ctx, queueId);
        if (!q.active) throw new ApiError(409, "This queue is currently closed.");

        for (QueueEntry e : ctx.store.entries.values()) {
            if (e.queueId.equals(queueId) && e.userId.equals(u.id)
                    && (e.status == EntryStatus.WAITING || e.status == EntryStatus.CALLED)) {
                throw new ApiError(409, "You're already in this queue.");
            }
        }

        QueueEntry e = new QueueEntry();
        e.id = Store.newId();
        e.queueId = queueId;
        e.userId = u.id;
        e.customerName = u.name;
        e.joinedAt = System.currentTimeMillis();
        e.status = EntryStatus.WAITING;
        ctx.store.entries.put(e.id, e);
        ctx.store.save();

        Main.sendJson(ctx.ex, 201, entrySummary(ctx.store, e));
    }

    public static void myEntry(Ctx ctx, String queueId) {
        User u = ctx.requireUser();
        requireQueue(ctx, queueId);
        QueueEntry found = null;
        for (QueueEntry e : ctx.store.entries.values()) {
            if (e.queueId.equals(queueId) && e.userId.equals(u.id)
                    && (e.status == EntryStatus.WAITING || e.status == EntryStatus.CALLED)) {
                found = e;
                break;
            }
        }
        if (found == null) { Main.sendJson(ctx.ex, 200, Json.obj("inQueue", false)); return; }
        Main.sendJson(ctx.ex, 200, entrySummary(ctx.store, found));
    }

    public static void listEntries(Ctx ctx, String queueId) {
        User u = ctx.requireUser();
        WaitQueue q = requireQueue(ctx, queueId);
        requireOwner(ctx, u, q);
        List<Object> out = new ArrayList<>();
        for (QueueEntry e : ctx.store.activeEntries(queueId)) out.add(entrySummary(ctx.store, e));
        Main.sendJson(ctx.ex, 200, out);
    }

    public static void callNext(Ctx ctx, String queueId) {
        User u = ctx.requireUser();
        WaitQueue q = requireQueue(ctx, queueId);
        requireOwner(ctx, u, q);
        List<QueueEntry> waiting = ctx.store.waitingEntries(queueId);
        if (waiting.isEmpty()) throw new ApiError(409, "No one is waiting in this queue.");
        QueueEntry next = waiting.get(0);
        next.status = EntryStatus.CALLED;
        next.calledAt = System.currentTimeMillis();
        ctx.store.save();
        Main.sendJson(ctx.ex, 200, entrySummary(ctx.store, next));
    }

    public static void markServed(Ctx ctx, String entryId) {
        User u = ctx.requireUser();
        QueueEntry e = requireEntry(ctx, entryId);
        WaitQueue q = ctx.store.queues.get(e.queueId);
        requireOwner(ctx, u, q);
        e.status = EntryStatus.SERVED;
        e.servedAt = System.currentTimeMillis();
        ctx.store.save();
        Main.sendJson(ctx.ex, 200, entrySummary(ctx.store, e));
    }

    public static void markNoShow(Ctx ctx, String entryId) {
        User u = ctx.requireUser();
        QueueEntry e = requireEntry(ctx, entryId);
        WaitQueue q = ctx.store.queues.get(e.queueId);
        requireOwner(ctx, u, q);
        e.status = EntryStatus.NO_SHOW;
        ctx.store.save();
        Main.sendJson(ctx.ex, 200, entrySummary(ctx.store, e));
    }

    public static void cancelEntry(Ctx ctx, String entryId) {
        User u = ctx.requireUser();
        QueueEntry e = requireEntry(ctx, entryId);
        if (!e.userId.equals(u.id)) throw new ApiError(403, "This isn't your queue entry.");
        e.status = EntryStatus.CANCELLED;
        ctx.store.save();
        Main.sendJson(ctx.ex, 200, entrySummary(ctx.store, e));
    }

    static Map<String, Object> entrySummary(Store store, QueueEntry e) {
        int position = store.positionOf(e);
        Business b = null;
        WaitQueue q = store.queues.get(e.queueId);
        if (q != null) b = store.businesses.get(q.businessId);
        int avgMin = b == null ? 5 : b.avgServiceMinutes;
        Integer etaMinutes = null;
        if (e.status == EntryStatus.WAITING && position > 0) {
            etaMinutes = (position - 1) * avgMin;
        }
        return Json.obj(
            "id", e.id, "queueId", e.queueId, "customerName", e.customerName,
            "status", e.status.name(), "position", position,
            "etaMinutes", etaMinutes, "joinedAt", e.joinedAt
        );
    }

    // ---------------- Helpers ----------------

    static WaitQueue requireQueue(Ctx ctx, String queueId) {
        WaitQueue q = ctx.store.queues.get(queueId);
        if (q == null) throw new ApiError(404, "Queue not found.");
        return q;
    }

    static QueueEntry requireEntry(Ctx ctx, String entryId) {
        QueueEntry e = ctx.store.entries.get(entryId);
        if (e == null) throw new ApiError(404, "Queue entry not found.");
        return e;
    }

    static void requireOwner(Ctx ctx, User u, WaitQueue q) {
        Business b = ctx.store.businesses.get(q.businessId);
        if (b == null || !b.ownerId.equals(u.id)) throw new ApiError(403, "You don't manage this queue.");
    }
}
