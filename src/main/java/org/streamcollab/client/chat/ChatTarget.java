package org.streamcollab.client.chat;

import java.util.Objects;

/** Application target, not a UDP peer ID (the immediate peer is the server). */
public sealed interface ChatTarget permits ChatTarget.Global, ChatTarget.Direct {
    String kind();

    record Global() implements ChatTarget {
        @Override public String kind() { return "GLOBAL"; }
    }

    record Direct(String userId) implements ChatTarget {
        public Direct {
            Objects.requireNonNull(userId, "userId");
            if (userId.isBlank() || userId.length() > 128 || userId.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid direct target user ID");
            }
        }
        @Override public String kind() { return "DIRECT"; }
    }
}
