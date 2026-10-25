package caseware.fan_out_worker.port;

import caseware.fan_out_worker.model.EngagementUpdateTask;

/**
 * Sends per-engagement update tasks to the work queue.
 * Downstream consumers drain this queue at bounded concurrency to protect
 * the engagement management system (~1 min/engagement, limited capacity).
 */
public interface UpdateTaskQueue {

    void send(EngagementUpdateTask task);
}