package caseware.fan_out_worker.model;

public record IndexedEngagement(
        String engagementId,
        String firmId,
        String region,
        String currentVersion
) {

    public IndexedEngagement {
        if (engagementId == null || engagementId.isBlank()) {
            throw new IllegalArgumentException("engagementId must not be blank");
        }
        if (firmId == null || firmId.isBlank()) {
            throw new IllegalArgumentException("firmId must not be blank");
        }
    }
}