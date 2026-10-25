package caseware.fan_out_worker.port;

import caseware.fan_out_worker.model.IndexedEngagement;

import java.util.List;
import java.util.Set;

/**
 * Queryable index mapping engagements to their current (template_id, version).
 * Populated by backfill, on-access capture, and engagement lifecycle hooks.
 */
public interface EngagementIndex {

    List<IndexedEngagement> findByTemplateAndVersions(
            String templateId,
            Set<String> versions,
            int page,
            int pageSize
    );
}