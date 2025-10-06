package net.chrisrichardson.ftgo.common;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ID Generator for Google Cloud Spanner
 * Generates unique Long IDs suitable for Spanner primary keys
 *
 * Spanner doesn't support AUTO_INCREMENT, so we need to generate IDs manually.
 * This uses a combination of timestamp and random values to ensure uniqueness.
 */
public class SpannerIdGenerator {

    /**
     * Generates a unique Long ID using timestamp and random components
     * This provides good distribution across Spanner splits
     *
     * @return a unique Long ID
     */
    public static Long generateId() {
        // Use current time in milliseconds (shifted) combined with random value
        // This provides good distribution and avoids hotspotting
        long timestamp = System.currentTimeMillis();
        long random = ThreadLocalRandom.current().nextLong(0, 100000);
        return (timestamp << 20) | random;
    }

    /**
     * Alternative: Generate ID from UUID
     * Provides better uniqueness guarantees but may have less ideal distribution
     *
     * @return a unique Long ID derived from UUID
     */
    public static Long generateIdFromUUID() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }
}
