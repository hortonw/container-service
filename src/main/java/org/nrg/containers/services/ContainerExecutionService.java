package org.nrg.containers.services;

import org.nrg.containers.events.DockerContainerEvent;
import org.nrg.containers.model.ContainerExecution;
import org.nrg.containers.model.ResolvedCommand;
import org.nrg.framework.orm.hibernate.BaseHibernateService;
import org.nrg.xft.security.UserI;

import java.util.Map;

public interface ContainerExecutionService extends BaseHibernateService<ContainerExecution> {
    void processEvent(final DockerContainerEvent event);
    void finalize(final Long containerExecutionId, final UserI userI);
    void finalize(final ContainerExecution containerExecution, final UserI userI);
    ContainerExecution save(final ResolvedCommand resolvedCommand,
                            final String containerId,
                            final UserI userI);
    String getStatus(final Long containerExecutionId, final UserI userI);
}
