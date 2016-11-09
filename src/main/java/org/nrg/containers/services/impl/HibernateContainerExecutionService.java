package org.nrg.containers.services.impl;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Hibernate;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.daos.ContainerExecutionRepository;
import org.nrg.containers.events.DockerContainerEvent;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.model.ContainerExecution;
import org.nrg.containers.model.ContainerExecutionHistory;
import org.nrg.containers.model.ResolvedCommand;
import org.nrg.containers.services.ContainerExecutionService;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.transporter.TransportService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xnat.restlet.util.XNATRestConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Service
public class HibernateContainerExecutionService
        extends AbstractHibernateEntityService<ContainerExecution, ContainerExecutionRepository>
        implements ContainerExecutionService {
    private static final Logger log = LoggerFactory.getLogger(HibernateContainerExecutionService.class);
    private final String UTF8 = StandardCharsets.UTF_8.name();

    private ContainerControlApi containerControlApi;
    private SiteConfigPreferences siteConfigPreferences;
    private AliasTokenService aliasTokenService;
    private TransportService transportService;

    @Autowired
    public HibernateContainerExecutionService(final ContainerControlApi containerControlApi,
                                              final SiteConfigPreferences siteConfigPreferences,
                                              final AliasTokenService aliasTokenService,
                                              final TransportService transportService) {
        this.containerControlApi = containerControlApi;
        this.siteConfigPreferences = siteConfigPreferences;
        this.aliasTokenService = aliasTokenService;
        this.transportService = transportService;
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
    }

    @Override
    @Transactional
    public void processEvent(final DockerContainerEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("Processing docker container event: " + event);
        }
        final List<ContainerExecution> matchingContainerIds = getDao().findByProperty("containerId", event.getContainerId());

        // Container ID is constrained to be unique, so we can safely take the first element of this list
        if (matchingContainerIds != null && !matchingContainerIds.isEmpty()) {
            final ContainerExecution execution = matchingContainerIds.get(0);
            if (log.isDebugEnabled()) {
                log.debug("Found matching execution: " + execution);
            }

            final ContainerExecutionHistory history = new ContainerExecutionHistory(event.getStatus(), event.getTime());
            if (log.isDebugEnabled()) {
                log.debug("Adding history entry: " + history);
            }
            execution.addToHistory(history);
            update(execution);

            if (StringUtils.isNotBlank(event.getStatus()) &&
                    event.getStatus().matches("kill|die|oom")) {
                final String userLogin = execution.getUserId();
                try {
                    final UserI userI = Users.getUser(userLogin);
                    finalize(execution, userI);
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
        finalize(containerExecution, userI);
    }

    @Override
    @Transactional
    public void finalize(final ContainerExecution containerExecution, final UserI userI) {
        if (log.isDebugEnabled()) {
            log.debug(String.format("Finalizing ContainerExecution for container %s", containerExecution.getContainerId()));
        }

        uploadLogs(containerExecution, userI);
        uploadOutputFiles(containerExecution, userI);


    }

    private void uploadLogs(final ContainerExecution execution, final UserI user) {
        String stdoutLogStr = "";
        String stderrLogStr = "";
        try {
            stdoutLogStr = containerControlApi.getContainerStdoutLog(execution.getContainerId());
            stderrLogStr = containerControlApi.getContainerStderrLog(execution.getContainerId());
        } catch (DockerServerException | NoServerPrefException e) {
            log.error("Could not get container logs for container with id " + execution.getContainerId(), e);
        }

        if (StringUtils.isNotBlank(stdoutLogStr) || StringUtils.isNotBlank(stderrLogStr)) {

//            final String containerExecDir = "";
//            PersistentWorkflowI wrk = null;
//            String rootPath = "";
//
//            CatCatalogBean cat = new CatCatalogBean();
//            cat.setId("LOG");
//
//
//
//
//            final String rootType = execution.getRootObjectXsiType();
//            if (StringUtils.isBlank(rootType)) {
//                // Execution was at the site level. Add logs to sitewide log resource.
//                rootPath = siteConfigPreferences.getArchivePath();
//            } else {
//                final XnatResourcecatalog catResource = new XnatResourcecatalog();
//
////                rootPath = item.
//
//
//                try {
//                    wrk = PersistentWorkflowUtils.buildOpenWorkflow(user, BaseElement.GetGeneratedItem(catResource).getItem(),
//                            EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.PROCESS, EventUtils.CREATE_RESOURCE));
//                } catch (PersistentWorkflowUtils.JustificationAbsent | PersistentWorkflowUtils.ActionNameAbsent | PersistentWorkflowUtils.IDAbsent e) {
//                    log.error("Could not create persistent workflow for resource upload.", e);
//                }
//            }

            final String archivePath = siteConfigPreferences.getArchivePath(); // TODO find a place to upload this thing. Root of the archive if sitewide, else under the archive path of the root object
            if (StringUtils.isNotBlank(archivePath)) {
                final SimpleDateFormat formatter = new SimpleDateFormat(XNATRestConstants.PREARCHIVE_TIMESTAMP);
                final String datestamp = formatter.format(new Date());
                final String containerExecPath = FileUtils.AppendRootPath(archivePath, "CONTAINER_EXEC/");
                final String destinationPath = containerExecPath + datestamp + "/LOGS/";
                final File destination = new File(destinationPath);
                destination.mkdirs();

                if (StringUtils.isNotBlank(stdoutLogStr)) {
                    final File stdoutFile = new File(destination, "stdout.log");
                    FileUtils.OutputToFile(stdoutLogStr, stdoutFile.getAbsolutePath());
                }

                if (StringUtils.isNotBlank(stderrLogStr)) {
                    final File stderrFile = new File(destination, "stderr.log");
                    FileUtils.OutputToFile(stderrLogStr, stderrFile.getAbsolutePath());
                }
            }

            // TODO Save a resource if possible.
            // session or subj or proj
            // .setResources_resource(catResource)

//            SaveItemHelper.authorizedSave(something, user, false, false, ci);
//            try {
//                WorkflowUtils.complete(wrk, ci);
//            } catch (Exception e) {
//                log.error("", e);
//            }
        }
    }

    private void uploadOutputFiles(final ContainerExecution containerExecution, final UserI userI) {

//        final List<CommandMount> toUpload = containerExecution.getMountsOut();
//        final String rootId = containerExecution.getRootObjectId();
//        final String rootXsiType = containerExecution.getRootObjectXsiType();
//        if (StringUtils.isNotBlank(rootXsiType) && StringUtils.isNotBlank(rootId)) {
//            final AliasToken token = aliasTokenService.issueTokenForUser(userI);
//
////            final String authToken = token.getAlias() + ":" + token.getSecret();
////            String encodedAuth = "";
////            try {
////                final byte[] authTokenBytes = authToken.getBytes("UTF-8");
////                final byte[] authTokenBase64Bytes = Base64.encode(authTokenBytes);
////                encodedAuth = new String(authTokenBase64Bytes, "UTF-8");
////            } catch (UnsupportedEncodingException e) {
////                log.error("Sorry, can't get an auth token", e);
////            }
//
//
////            final String xnatUrl = siteConfigPreferences.getSiteUrl();
//            final String xnatUrl = "http://localhost:80";
//            final HttpHost targetHost = new HttpHost(xnatUrl);
//            String url = xnatUrl + "/data";
//
//            if (rootXsiType.matches(".+?:.*?[Ss]can.*") && rootId.contains(":")) {
//                final String scanId = StringUtils.substringAfterLast(rootId, ":");
//                final String sessionId = StringUtils.substringBeforeLast(rootId, ":");
//
//                url += String.format("/experiments/%s/scans/%s", sessionId, scanId);
//            } else if (rootXsiType.matches(".+?:.*?[Ss]ubject.*")) {
//                url += String.format("/subjects/%s", rootId);
//            } else if (rootXsiType.matches(".+?:.*?[Pp]roject.*")) {
//                url += String.format("/projects/%s", rootId);
//            } else {
//                // If all else fails, it's an experiment
//                url += String.format("/experiments/%s", rootId);
//            }
//
////            final HttpClientBuilder clientBuilder = HttpClientBuilder.create();
////            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
////            credentialsProvider.setCredentials(new AuthScope(targetHost.getHostName(), targetHost.getPort()), new UsernamePasswordCredentials(token.getAlias(), token.getSecret()));
////            final AuthCache authCache = new BasicAuthCache();
////            final BasicScheme basicAuth = new BasicScheme();
////            authCache.put(targetHost, basicAuth);
////            clientBuilder.setDefaultCredentialsProvider(credentialsProvider);
////            final HttpClientContext clientContext = new HttpClientContext();
////            clientContext.setAuthCache(authCache);
////            clientContext.setCredentialsProvider(credentialsProvider);
////            try (final CloseableHttpClient client = clientBuilder.build()) {
////            try (final CloseableHttpClient client = HttpClients.createDefault()) {
//                for (final CommandMount mount : toUpload) {
//                    if (StringUtils.isBlank(mount.getName())) {
//                        log.error(String.format("Cannot upload mount for container execution %s. Mount has no resource name. %s",
//                                containerExecution.getId(), mount));
//                        continue;
//                    }
//                    if (StringUtils.isBlank(mount.getHostPath())) {
//                        log.error(String.format("Cannot upload mount for container execution %s. Mount has no path to files. %s",
//                                containerExecution.getId(), mount));
//                        continue;
//                    }
//                    final Path pathOnExecutionMachine = Paths.get(mount.getHostPath());
//                    final Path pathOnXnatMachine = transportService.transport("", pathOnExecutionMachine); // TODO this currently does nothing
//                    url += String.format("/resources/%s/files", mount.getName());
//                    try {
//                        url += String.format("?overwrite=%s&reference=%s",
//                                URLEncoder.encode(mount.getOverwrite().toString(), UTF8),
//                                URLEncoder.encode(pathOnXnatMachine.toString(), UTF8));
//                    } catch (IOException e) {
//                        log.error(String.format("Cannot upload mount for container execution %s. There was an error. %s", containerExecution.getId(), mount), e);
//                    }
//
////                        final HttpPost post = new HttpPost(url);
////                        post.setHeader("Authorization", "Basic " + encodedAuth);
//
////                        final HttpResponse response = client.execute(post, clientContext);
////                        final HttpResponse response = client.execute(post);
//
////                    try {
////                        final Response response = given().auth().preemptive().basic(token.getAlias(), token.getSecret()).when().post(url).andReturn();
////                        if (response.getStatusCode() > 400) {
////                            log.error(String.format("Upload failed for container execution %s, mount %s.\n" +
////                                            "Attempted POST %s.\nUpload returned response: %s",
////                                    containerExecution.getId(), mount, url, response.getStatusLine()));
////                        }
////                    } catch (Exception e) {
////                        log.error(String.format("Upload failed for container execution %s, mount %s.\n" +
////                                        "Attempted POST %s.\nGot an exception.",
////                                containerExecution.getId(), mount, url), e);
////                    }
//                    // TODO Actually upload files as new resource
//
//
//                }
////            } catch (IOException e) {
////                log.error(String.format("Cannot upload files for container execution %s. Could not connect out and back to XNAT.", containerExecution.getId()));
////            }
//        } else {
//            // No root id and/or xsi type, so I can't upload anything.
//            // If there is anything there that I am supposed to upload, I will log that fact.
//            if (toUpload != null && !toUpload.isEmpty()) {
//                log.error("Cannot upload outputs for container execution " + containerExecution.getId() + ". No root id and/or xsi type.");
//            }
//        }
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
    public String getStatus(final Long containerExecutionId, final UserI userI) {
        final ContainerExecution execution = retrieve(containerExecutionId);
        String containerStatus = "";
        try {
            containerStatus  = containerControlApi.getContainerStdoutLog(execution.getContainerId());
        } catch (DockerServerException | NoServerPrefException e) {
            log.error("Could not get container status for container with id " + execution.getContainerId(), e);
        }
        return containerStatus;
    }

}
