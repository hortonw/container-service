package org.nrg.containers.daos;

import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.events.DockerContainerEvent;
import org.nrg.containers.model.ContainerExecution;
import org.nrg.containers.model.ContainerExecutionHistory;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ContainerExecutionRepository extends AbstractHibernateDAO<ContainerExecution> {
    private static final Logger log = LoggerFactory.getLogger(ContainerExecutionRepository.class);

    public ContainerExecution didRecordEvent(final DockerContainerEvent event) {

        final List<ContainerExecution> matchingContainerIds = findByProperty("containerId", event.getContainerId());

        // Container ID is constrained to be unique, so we can safely take the first element of this list
        if (matchingContainerIds != null && !matchingContainerIds.isEmpty()) {
            final ContainerExecution execution = matchingContainerIds.get(0);
            if (log.isDebugEnabled()) {
                log.debug("Found matching execution: " + execution.getId());
            }

            String status = event.getStatus();
            if (status.matches("kill|die|oom")) {
                final Map<String, Object> attributes = event.getAttributes();
                final String exitCode =
                        attributes.containsKey("exitCode") &&
                                attributes.get("exitCode") != null &&
                                StringUtils.isNotBlank((String)attributes.get("exitCode")) ?
                                (String) attributes.get("exitCode") :
                                "x";

                status += "(" + exitCode + ")";
            }
            final ContainerExecutionHistory newHistory =
                    new ContainerExecutionHistory(status, event.getTimeNano());

            final List<ContainerExecutionHistory> historyList = execution.getHistory();
            if (historyList != null && !historyList.isEmpty()) {
                for (final ContainerExecutionHistory history : historyList) {
                    if (history.equals(newHistory)) {
                        if (log.isDebugEnabled()) {
                            log.debug("Event has already been recorded in the history.");
                        }
                        return null;
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("Adding history entry: " + newHistory);
            }
            execution.addToHistory(newHistory);
            update(execution);

            return execution;
        } else {
            if (log.isDebugEnabled()) {
                log.debug("No execution found for container " + event.getContainerId());
            }
        }

        return null;
    }
}
