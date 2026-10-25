package caseware.fan_out_worker.port;

import java.util.Set;

/**
 * Resolves which existing template versions are superseded by a newly published version.
 * Handles non-linear version graphs: branching, withdrawals, accumulated changes.
 */
public interface TemplateVersionResolver {

    Set<String> findSupersededVersions(String templateId, String newVersion);
}