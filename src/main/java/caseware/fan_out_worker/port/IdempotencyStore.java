package caseware.fan_out_worker.port;

/**
 * Tracks which (template, version) publish events have been fully processed by the fan-out.
 * Prevents duplicate work on JMS redelivery or broker failover.
 */
public interface IdempotencyStore {

    boolean isProcessed(String templateId, String version);

    void markProcessed(String templateId, String version, int engagementCount);
}