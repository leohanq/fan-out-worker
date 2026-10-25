package caseware.fan_out_worker.model;

import java.io.Serializable;

public record EngagementUpdateTask(
        String engagementId,
        String firmId,
        String templateId,
        String fromVersion,
        String toVersion
) implements Serializable {

    public EngagementUpdateTask {
        if (engagementId == null || engagementId.isBlank()) {
            throw new IllegalArgumentException("engagementId must not be blank");
        }
        if (firmId == null || firmId.isBlank()) {
            throw new IllegalArgumentException("firmId must not be blank");
        }
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must not be blank");
        }
        if (fromVersion == null || fromVersion.isBlank()) {
            throw new IllegalArgumentException("fromVersion must not be blank");
        }
        if (toVersion == null || toVersion.isBlank()) {
            throw new IllegalArgumentException("toVersion must not be blank");
        }
    }
}