package caseware.fan_out_worker.worker;

import caseware.fan_out_worker.model.EngagementUpdateTask;
import caseware.fan_out_worker.model.IndexedEngagement;
import caseware.fan_out_worker.model.TemplatePublishedEvent;
import caseware.fan_out_worker.port.EngagementIndex;
import caseware.fan_out_worker.port.IdempotencyStore;
import caseware.fan_out_worker.port.TemplateVersionResolver;
import caseware.fan_out_worker.port.UpdateTaskQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class FanOutWorker {

    static final int PAGE_SIZE = 500;

    private final TemplateVersionResolver versionResolver;
    private final EngagementIndex engagementIndex;
    private final IdempotencyStore idempotencyStore;
    private final UpdateTaskQueue taskQueue;

    /**
     * Receives a template-published event and fans out one work item per affected engagement.
     *
     * <p>Concurrency: single-threaded — only one publish event is processed at a time.
     * At ~40 publishes/week total, serial processing is sufficient and avoids
     * concurrent fan-outs competing for the same index/queue resources.</p>
     *
     * <p>Failure: any exception propagates to the JMS container, which redelivers
     * with exponential backoff. After max retries the message goes to DLQ.
     * Partial fan-outs (crash after sending some tasks) are safe because
     * downstream consumers are independently idempotent.</p>
     *
     * <p>Idempotency: checked via IdempotencyStore before doing work.
     * On replay after partial fan-out, some tasks are dispatched twice —
     * acceptable because the downstream consumer deduplicates.</p>
     *
     * <p>Downstream capacity: the queue decouples this worker from the ~1 min/engagement
     * processing. Consumer concurrency + prefetch limits cap the load on the
     * engagement management system.</p>
     */
    @JmsListener(
            destination = "${fanout.source-topic:template.published}",
            concurrency = "1"
    )
    public void onTemplatePublished(TemplatePublishedEvent event) {
        String templateId = event.templateId();
        String version = event.version();

        log.info("Received template-published event: template={} version={}", templateId, version);

        if (idempotencyStore.isProcessed(templateId, version)) {
            log.info("Already processed template={} version={}, skipping", templateId, version);
            return;
        }

        Set<String> superseded = versionResolver.findSupersededVersions(templateId, version);
        if (superseded.isEmpty()) {
            log.info("No superseded versions for template={} version={}", templateId, version);
            idempotencyStore.markProcessed(templateId, version, 0);
            return;
        }

        int totalDispatched = dispatchUpdateTasks(templateId, version, superseded);

        idempotencyStore.markProcessed(templateId, version, totalDispatched);
        log.info("Fan-out complete: template={} version={} dispatched={}", templateId, version, totalDispatched);
    }

    private int dispatchUpdateTasks(String templateId, String toVersion, Set<String> supersededVersions) {
        int totalDispatched = 0;
        int page = 0;

        List<IndexedEngagement> batch;
        do {
            batch = engagementIndex.findByTemplateAndVersions(templateId, supersededVersions, page, PAGE_SIZE);

            for (IndexedEngagement engagement : batch) {
                taskQueue.send(new EngagementUpdateTask(
                        engagement.engagementId(),
                        engagement.firmId(),
                        templateId,
                        engagement.currentVersion(),
                        toVersion
                ));
                totalDispatched++;
            }

            page++;
        } while (batch.size() == PAGE_SIZE);

        return totalDispatched;
    }
}