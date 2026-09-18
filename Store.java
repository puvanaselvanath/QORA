package qora;

import qora.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Thread-safe in-memory store with persistence to a single binary file
 * (data/qora.db) using Java serialization. No external database is used —
 * this keeps QORA runnable with nothing but a JDK, which matters since this
 * environment has no build tool or network access to fetch a real DB driver.
 * Swap this class out for a JPA/JDBC-backed implementation later without
 * touching the HTTP handlers, since they only talk to Store's methods.
 */
public class Store implements Serializable {

    public final Map<String, User> usersById = new ConcurrentHashMap<>();
    public final Map<String, String> emailToUserId = new ConcurrentHashMap<>();
    public final Map<String, String> phoneToUserId = new ConcurrentHashMap<>();
    public final Map<String, Business> businesses = new ConcurrentHashMap<>();
    public final Map<String, WaitQueue> queues = new ConcurrentHashMap<>();
    public final Map<String, QueueEntry> entries = new ConcurrentHashMap<>();

    public transient Map<String, String> tokenToUserId = new ConcurrentHashMap<>();

    private static final Path DB_PATH = resolveDbPath();

    /**
     * Puts qora.db in a data/ folder beside the running jar, so a double-clicked
     * jar always saves to the same predictable place regardless of what the OS
     * sets as the working directory. Falls back to a relative path if the jar
     * location can't be determined (e.g. running from compiled classes).
     */
    private static Path resolveDbPath() {
        try {
            Path self = Paths.get(Store.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path base = Files.isDirectory(self) ? self : self.getParent();
            if (base != null && Files.isWritable(base)) {
                return base.resolve("data").resolve("qora.db");
            }
        } catch (Exception ignored) {
        }
        return Paths.get("data", "qora.db");
    }

    public static synchronized Store load() {
        if (Files.exists(DB_PATH)) {
            try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(DB_PATH)))) {
                Store s = (Store) in.readObject();
                s.tokenToUserId = new ConcurrentHashMap<>();
                System.out.println("[QORA] Loaded existing data: " + s.usersById.size() + " users, "
                        + s.businesses.size() + " businesses, " + s.queues.size() + " queues.");
                return s;
            } catch (Exception e) {
                System.out.println("[QORA] Could not load existing data (" + e.getMessage() + "), starting fresh.");
            }
        }
        return new Store();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(DB_PATH.getParent());
            Path tmp = Paths.get(DB_PATH.toString() + ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeObject(this);
            }
            Files.move(tmp, DB_PATH, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[QORA] Failed to save data: " + e.getMessage());
        }
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // ---------- Derived queries ----------

    /** WAITING entries for a queue, ordered by join time (FIFO). */
    public List<QueueEntry> waitingEntries(String queueId) {
        List<QueueEntry> list = new ArrayList<>();
        for (QueueEntry e : entries.values()) {
            if (e.queueId.equals(queueId) && e.status == EntryStatus.WAITING) list.add(e);
        }
        list.sort(Comparator.comparingLong(e -> e.joinedAt));
        return list;
    }

    /** All active (WAITING or CALLED) entries for a queue, for the staff dashboard. */
    public List<QueueEntry> activeEntries(String queueId) {
        List<QueueEntry> list = new ArrayList<>();
        for (QueueEntry e : entries.values()) {
            if (e.queueId.equals(queueId) && (e.status == EntryStatus.WAITING || e.status == EntryStatus.CALLED)) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparingLong(e -> e.joinedAt));
        return list;
    }

    /** 1-based position of an entry within its queue's WAITING list, or -1 if not waiting. */
    public int positionOf(QueueEntry entry) {
        if (entry.status != EntryStatus.WAITING) return -1;
        List<QueueEntry> waiting = waitingEntries(entry.queueId);
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).id.equals(entry.id)) return i + 1;
        }
        return -1;
    }

    public List<WaitQueue> queuesForBusiness(String businessId) {
        List<WaitQueue> list = new ArrayList<>();
        for (WaitQueue q : queues.values()) if (q.businessId.equals(businessId)) list.add(q);
        return list;
    }

    public List<Business> allBusinesses() {
        return new ArrayList<>(businesses.values());
    }
}
