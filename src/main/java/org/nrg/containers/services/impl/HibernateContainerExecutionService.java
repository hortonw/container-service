package org.nrg.containers.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.Hibernate;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.daos.ContainerExecutionRepository;
import org.nrg.containers.events.DockerContainerEvent;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.helpers.ContainerFinalizeHelper;
import org.nrg.containers.model.ContainerExecution;
import org.nrg.containers.model.ContainerExecutionHistory;
import org.nrg.containers.model.ResolvedCommand;
import org.nrg.containers.services.ContainerExecutionService;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.transporter.TransportService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.archive.CatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HibernateContainerExecutionService
        extends AbstractHibernateEntityService<ContainerExecution, ContainerExecutionRepository>
        implements ContainerExecutionService {
    private static final Logger log = LoggerFactory.getLogger(HibernateContainerExecutionService.class);
    private static final Pattern exitCodePattern = Pattern.compile("kill|die|oom\\((\\d+|x)\\)");
    private final String UTF8 = StandardCharsets.UTF_8.name();

    private ContainerControlApi containerControlApi;
    private SiteConfigPreferences siteConfigPreferences;
    private TransportService transportService;
    private PermissionsServiceI permissionsService;
    private CatalogService catalogService;
    private ObjectMapper mapper;

    @Autowired
    public HibernateContainerExecutionService(final ContainerControlApi containerControlApi,
                                              final SiteConfigPreferences siteConfigPreferences,
                                              final TransportService transportService,
                                              final PermissionsServiceI permissionsService,
                                              final CatalogService catalogService,
                                              final ObjectMapper mapper) {
        this.containerControlApi = containerControlApi;
        this.siteConfigPreferences = siteConfigPreferences;
        this.transportService = transportService;
        this.permissionsService = permissionsService;
        this.catalogService = catalogService;
        this.mapper = mapper;
    }

    @Override
    public void initialize(final ContainerExecution entity) {
        if (entity == null) {
            return;
        }
        Hibernate.initialize(entity);
        Hibernate.initialize(entity.getEnvironmentVariables());
        Hibernate.initialize(entity.getHistory());
        Hibernate.initialize(entity.getMountsIn());
        Hibernate.initialize(entity.getMountsOut());
        Hibernate.initialize(entity.getCommandLine());
        Hibernate.initialize(entity.getInputValues());
        Hibernate.initialize(entity.getOutputs());
    }

    @Override
    @Transactional
    public void processEvent(final DockerContainerEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("Processing docker container event: " + event);
        }
        final ContainerExecution execution = getDao().didRecordEvent(event);

        // execution will be null if either we aren't tracking the container
        // that this event is about, or if we have already recorded the event
        if (execution != null ) {

            final Matcher exitCodeMatcher =
                    exitCodePattern.matcher(event.getStatus());
            if (exitCodeMatcher.matches()) {
                final String exitCode = exitCodeMatcher.group(1);
                final String userLogin = execution.getUserId();
                try {
                    final UserI userI = Users.getUser(userLogin);
                    finalize(execution, userI, exitCode);
                } catch (UserInitException | UserNotFoundException e) {
                    log.error("Could not finalize container execution. Could not get user details for user " + userLogin, e);
                }

            }
        }
    }

    @Override
    @Transactional
    public void finalize(final Long containerExecutionId, final UserI userI) {
        final ContainerExecution containerExecution = retrieve(containerExecutionId);
        String exitCode = "x";
        for (final ContainerExecutionHistory history : containerExecution.getHistory()) {
            final Matcher exitCodeMatcher = exitCodePattern.matcher(history.getStatus());
            if (exitCodeMatcher.matches()) {
                exitCode = exitCodeMatcher.group(1);
            }
        }
        finalize(containerExecution, userI, exitCode);
    }

    @Override
    @Transactional
    public void finalize(final ContainerExecution containerExecution, final UserI userI, final String exitCode) {
        if (log.isDebugEnabled()) {
            log.debug(String.format("Finalizing ContainerExecution %s for container %s", containerExecution.getId(), containerExecution.getContainerId()));
        }

        ContainerFinalizeHelper.finalizeContainer(containerExecution, userI, exitCode, containerControlApi, siteConfigPreferences, transportService, permissionsService, catalogService, mapper);
    }

    @Override
    @Transactional
    public ContainerExecution save(final ResolvedCommand resolvedCommand,
                                   final String containerId,
                                   final UserI userI) {
        final ContainerExecution execution = new ContainerExecution(resolvedCommand, containerId, userI.getLogin());
        return create(execution);
    }

    @Override
    @Transactional
    public String kill(final Long containerExecutionId, final UserI userI)
            throws NoServerPrefException, DockerServerException, NotFoundException {
        // TODO check user permissions. How?
        final ContainerExecution containerExecution = retrieve(containerExecutionId);
        final String containerId = containerExecution.getContainerId();
        containerControlApi.killContainer(containerId);
        return containerId;
    }
}
