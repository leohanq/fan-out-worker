package caseware.fan_out_worker.worker;

import caseware.fan_out_worker.model.EngagementUpdateTask;
import caseware.fan_out_worker.model.IndexedEngagement;
import caseware.fan_out_worker.model.TemplatePublishedEvent;
import caseware.fan_out_worker.port.EngagementIndex;
import caseware.fan_out_worker.port.IdempotencyStore;
import caseware.fan_out_worker.port.TemplateVersionResolver;
import caseware.fan_out_worker.port.UpdateTaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FanOutWorkerTest {

    @Mock TemplateVersionResolver versionResolver;
    @Mock EngagementIndex engagementIndex;
    @Mock IdempotencyStore idempotencyStore;
    @Mock UpdateTaskQueue taskQueue;
    @Captor ArgumentCaptor<EngagementUpdateTask> taskCaptor;

    FanOutWorker worker;

    @BeforeEach
    void setUp() {
        worker = new FanOutWorker(versionResolver, engagementIndex, idempotencyStore, taskQueue);
    }

    // --- Happy path ---

    @Test
    void fansOutToAllAffectedEngagements() {
        var event = new TemplatePublishedEvent("tmpl-A", "v6", Instant.now());
        when(idempotencyStore.isProcessed("tmpl-A", "v6")).thenReturn(false);
        when(versionResolver.findSupersededVersions("tmpl-A", "v6"))
                .thenReturn(Set.of("v3", "v4", "v5"));
        when(engagementIndex.findByTemplateAndVersions(eq("tmpl-A"), eq(Set.of("v3", "v4", "v5")), eq(0), eq(FanOutWorker.PAGE_SIZE)))
                .thenReturn(List.of(
                        new IndexedEngagement("eng-1", "firm-A", "US", "v3"),
                        new IndexedEngagement("eng-2", "firm-A", "US", "v4"),
                        new IndexedEngagement("eng-3", "firm-B", "EU", "v5")
                ));

        worker.onTemplatePublished(event);

        verify(taskQueue, times(3)).send(taskCaptor.capture());
        List<EngagementUpdateTask> tasks = taskCaptor.getAllValues();

        assertThat(tasks).extracting(EngagementUpdateTask::engagementId)
                .containsExactly("eng-1", "eng-2", "eng-3");
        assertThat(tasks).allMatch(t -> t.toVersion().equals("v6"));
        assertThat(tasks).extracting(EngagementUpdateTask::fromVersion)
                .containsExactly("v3", "v4", "v5");

        verify(idempotencyStore).markProcessed("tmpl-A", "v6", 3);
    }

    // --- Idempotency ---

    @Test
    void skipsAlreadyProcessedEvent() {
        var event = new TemplatePublishedEvent("tmpl-A", "v6", Instant.now());
        when(idempotencyStore.isProcessed("tmpl-A", "v6")).thenReturn(true);

        worker.onTemplatePublished(event);

        verifyNoInteractions(versionResolver, engagementIndex, taskQueue);
        verify(idempotencyStore, never()).markProcessed(anyString(), anyString(), anyInt());
    }

    // --- Empty cases ---

    @Test
    void noSupersededVersions_marksProcessedWithZero() {
        var event = new TemplatePublishedEvent("tmpl-A", "v1", Instant.now());
        when(idempotencyStore.isProcessed("tmpl-A", "v1")).thenReturn(false);
        when(versionResolver.findSupersededVersions("tmpl-A", "v1"))
                .thenReturn(Collections.emptySet());

        worker.onTemplatePublished(event);

        verifyNoInteractions(engagementIndex, taskQueue);
        verify(idempotencyStore).markProcessed("tmpl-A", "v1", 0);
    }

    @Test
    void noAffectedEngagements_marksProcessedWithZero() {
        var event = new TemplatePublishedEvent("tmpl-A", "v6", Instant.now());
        when(idempotencyStore.isProcessed("tmpl-A", "v6")).thenReturn(false);
        when(versionResolver.findSupersededVersions("tmpl-A", "v6"))
                .thenReturn(Set.of("v5"));
        when(engagementIndex.findByTemplateAndVersions(eq("tmpl-A"), eq(Set.of("v5")), eq(0), eq(FanOutWorker.PAGE_SIZE)))
                .thenReturn(Collections.emptyList());

        worker.onTemplatePublished(event);

        verifyNoInteractions(taskQueue);
        verify(idempotencyStore).markProcessed("tmpl-A", "v6", 0);
    }

    // --- Large fan-out with pagination ---

    @Test
    void paginatesThroughLargeFanOut() {
        var event = new TemplatePublishedEvent("tmpl-B", "v3", Instant.now());
        when(idempotencyStore.isProcessed("tmpl-B", "v3")).thenReturn(false);
        when(versionResolver.findSupersededVersions("tmpl-B", "v3"))
                .thenReturn(Set.of("v1", "v2"));

        List<IndexedEngagement> fullPage = engagements("firm-X", "v2", FanOutWorker.PAGE_SIZE);
        List<IndexedEngagement> lastPage = engagements("firm-X", "v2", 123);

        when(engagementIndex.findByTemplateAndVersions(eq("tmpl-B"), eq(Set.of("v1", "v2")), eq(0), eq(FanOutWorker.PAGE_SIZE)))
                .thenReturn(fullPage);
        when(engagementIndex.findByTemplateAndVersions(eq("tmpl-B"), eq(Set.of("v1", "v2")), eq(1), eq(FanOutWorker.PAGE_SIZE)))
                .thenReturn(lastPage);

        worker.onTemplatePublished(event);

        int expected = FanOutWorker.PAGE_SIZE + 123;
        verify(taskQueue, times(expected)).send(any());
        verify(idempotencyStore).markProcessed("tmpl-B", "v3", expected);
        verify(engagementIndex, times(2)).findByTemplateAndVersions(anyString(), anySet(), anyInt(), anyInt());
    }

    // --- Failure propagation ---

    @Test
    void versionResolverFailure_propagatesForRedelivery() {
        var event = new TemplatePublishedEvent("tmpl-A", "v6", Instant.now());
        when(idempotencyStore.isProcessed("tmpl-A", "v6")).thenReturn(false);
        when(versionResolver.findSupersededVersions("tmpl-A", "v6"))
                .thenThrow(new RuntimeException("Template DB unavailable"));

        assertThatThrownBy(() -> worker.onTemplatePublished(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Template DB unavailable");

        verify(idempotencyStore, never()).markProcessed(anyString(), anyString(), anyInt());
        verifyNoInteractions(taskQueue);
    }

    @Test
    void indexQueryFailure_propagatesForRedelivery() {
        var event = new TemplatePublishedEvent("tmpl-A", "v6", Instant.now());
        when(idempotencyStore.isProcessed("tmpl-A", "v6")).thenReturn(false);
        when(versionResolver.findSupersededVersions("tmpl-A", "v6"))
                .thenReturn(Set.of("v5"));
        when(engagementIndex.findByTemplateAndVersions(anyString(), anySet(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Index DB unavailable"));

        assertThatThrownBy(() -> worker.onTemplatePublished(event))
                .isInstanceOf(RuntimeException.class);

        verify(idempotencyStore, never()).markProcessed(anyString(), anyString(), anyInt());
    }

    @Test
    void queueSendFailureMidFanOut_doesNotMarkProcessed() {
        var event = new TemplatePublishedEvent("tmpl-A", "v6", Instant.now());
        when(idempotencyStore.isProcessed("tmpl-A", "v6")).thenReturn(false);
        when(versionResolver.findSupersededVersions("tmpl-A", "v6"))
                .thenReturn(Set.of("v5"));
        when(engagementIndex.findByTemplateAndVersions(anyString(), anySet(), eq(0), anyInt()))
                .thenReturn(List.of(
                        new IndexedEngagement("eng-1", "firm-A", "US", "v5"),
                        new IndexedEngagement("eng-2", "firm-A", "US", "v5")
                ));

        doNothing().when(taskQueue).send(argThat(t -> t.engagementId().equals("eng-1")));
        doThrow(new RuntimeException("Queue full"))
                .when(taskQueue).send(argThat(t -> t.engagementId().equals("eng-2")));

        assertThatThrownBy(() -> worker.onTemplatePublished(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Queue full");

        verify(taskQueue, times(2)).send(any());
        verify(idempotencyStore, never()).markProcessed(anyString(), anyString(), anyInt());
    }

    // --- Correct task content ---

    @Test
    void taskCarriesCorrectFromAndToVersions() {
        var event = new TemplatePublishedEvent("tmpl-C", "v7", Instant.now());
        when(idempotencyStore.isProcessed("tmpl-C", "v7")).thenReturn(false);
        when(versionResolver.findSupersededVersions("tmpl-C", "v7"))
                .thenReturn(Set.of("v2", "v5"));
        when(engagementIndex.findByTemplateAndVersions(eq("tmpl-C"), eq(Set.of("v2", "v5")), eq(0), anyInt()))
                .thenReturn(List.of(
                        new IndexedEngagement("eng-10", "firm-Z", "CA", "v2"),
                        new IndexedEngagement("eng-11", "firm-Z", "CA", "v5")
                ));

        worker.onTemplatePublished(event);

        verify(taskQueue, times(2)).send(taskCaptor.capture());
        List<EngagementUpdateTask> tasks = taskCaptor.getAllValues();

        assertThat(tasks.get(0)).isEqualTo(
                new EngagementUpdateTask("eng-10", "firm-Z", "tmpl-C", "v2", "v7"));
        assertThat(tasks.get(1)).isEqualTo(
                new EngagementUpdateTask("eng-11", "firm-Z", "tmpl-C", "v5", "v7"));
    }

    // --- Helpers ---

    private static List<IndexedEngagement> engagements(String firmId, String version, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new IndexedEngagement("eng-" + i, firmId, "US", version))
                .toList();
    }
}