package com.celtech.solutions.cgsKitchen.models.webhooks;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Append-only audit + dedup record for every webhook received.
 *
 * <p><strong>Dedup:</strong> the unique index on {@code (provider, eventId)}
 * means a duplicate webhook from the same provider with the same event id
 * cannot be inserted twice — we use that to short-circuit duplicate
 * processing.
 *
 * <p><strong>Audit:</strong> we keep the raw payload and processing result
 * so you can answer "what did we receive and how did we handle it" weeks
 * later. TTL index on {@code receivedAt} purges records older than
 * {@link #TTL_DAYS} days to keep the collection bounded.
 *
 * <p>This collection is the source of truth for the
 * {@code /admin/debug/replay-webhook} endpoint — it reads from here to
 * re-fire historical events.
 */
@Document(collection = "webhook_events")
@CompoundIndexes({
        @CompoundIndex(name = "provider_event_idx",
                def = "{'provider': 1, 'eventId': 1}",
                unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEvent {

    public static final int TTL_DAYS = 60;

    @Id
    private String id;

    /** "stripe" or "uber". */
    private String provider;

    /** Provider's event id (Stripe: evt_..., Uber: evt_...). */
    private String eventId;

    /** Event kind/type — purely for queries/debugging, not used for dedup. */
    private String eventType;

    /** Raw payload as received. Allows replay and forensic analysis. */
    private String payload;

    /** Processing outcome — "processed", "skipped", "failed". */
    private String result;

    /** Free-form notes when result is "skipped" or "failed". */
    private String resultDetail;

    /**
     * TTL anchor — Mongo expires the document {@link #TTL_DAYS} days after
     * this. Index defined programmatically in MongoIndexConfig.
     */
    @Indexed
    private Instant receivedAt;

    /** When processing actually finished — could be null for in-flight. */
    private Instant processedAt;
}