package qora;

import java.io.Serializable;
import java.util.*;

public class Models {

    public enum Role { CUSTOMER, BUSINESS_OWNER }
    public enum EntryStatus { WAITING, CALLED, SERVED, CANCELLED, NO_SHOW }

    public static class User implements Serializable {
        public String id;
        public String name;
        public String email;   // may be null
        public String phone;   // may be null
        public String passwordHash;
        public String salt;
        public Role role;
        public long createdAt;

        public Map<String, Object> toPublic() {
            return Json.obj(
                "id", id, "name", name, "email", email, "phone", phone,
                "role", role.name()
            );
        }
    }

    public static class Business implements Serializable {
        public String id;
        public String ownerId;
        public String name;
        public String category;
        public int avgServiceMinutes = 5;
        public long createdAt;

        public Map<String, Object> toPublic() {
            return Json.obj(
                "id", id, "ownerId", ownerId, "name", name,
                "category", category, "avgServiceMinutes", avgServiceMinutes
            );
        }
    }

    public static class WaitQueue implements Serializable {
        public String id;
        public String businessId;
        public String name;
        public boolean active = true;
        public long createdAt;
    }

    public static class QueueEntry implements Serializable {
        public String id;
        public String queueId;
        public String userId;
        public String customerName; // denormalized for dashboard convenience
        public long joinedAt;
        public EntryStatus status = EntryStatus.WAITING;
        public long calledAt;
        public long servedAt;
    }
}
