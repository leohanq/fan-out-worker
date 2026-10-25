package caseware.fan_out_worker.model;

import java.io.Serializable;
import java.time.Instant;

public record TemplatePublishedEvent(
        String templateId,
        String version,
        Instant publishedAt
) implements Serializable {

    public TemplatePublishedEvent {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (publishedAt == null) {
            publishedAt = Instant.now();
        }
    }
}