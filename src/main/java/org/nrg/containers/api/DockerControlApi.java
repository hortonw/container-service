package org.nrg.containers.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerCertificates;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerClient.LogsParam;
import com.spotify.docker.client.EventStream;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.LoggingPullHandler;
import com.spotify.docker.client.ProgressHandler;
import com.spotify.docker.client.exceptions.ContainerNotFoundException;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.exceptions.ImageNotFoundException;
import com.spotify.docker.client.messages.AuthConfig;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.Event;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.Image;
import com.spotify.docker.client.messages.ImageInfo;
import com.spotify.docker.client.messages.PortBinding;
import com.spotify.docker.client.messages.ProgressMessage;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.events.DockerContainerEvent;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.model.Command;
import org.nrg.containers.model.Container;
import org.nrg.containers.model.ContainerExecutionMount;
import org.nrg.containers.model.DockerHub;
import org.nrg.containers.model.DockerImage;
import org.nrg.containers.model.DockerServer;
import org.nrg.containers.model.DockerServerPrefsBean;
import org.nrg.containers.model.ResolvedCommand;
import org.nrg.framework.services.NrgEventService;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.spotify.docker.client.DockerClient.EventsParam.EventType.CONTAINER;
import static com.spotify.docker.client.DockerClient.EventsParam.since;
import static com.spotify.docker.client.DockerClient.EventsParam.type;
import static com.spotify.docker.client.DockerClient.EventsParam.until;

@Service
public class DockerControlApi implements ContainerControlApi {
    private static final Logger log = LoggerFactory.getLogger(DockerControlApi.class);

    private DockerServerPrefsBean containerServerPref;
    private ObjectMapper objectMapper;
    private NrgEventService eventService;

    @Autowired
    public DockerControlApi(final DockerServerPrefsBean containerServerPref,
                            final ObjectMapper objectMapper,
                            final NrgEventService eventService) {
        this.containerServerPref = containerServerPref;
        this.objectMapper = objectMapper;
        this.eventService = eventService;
    }

    public DockerServer getServer() throws NoServerPrefException {
        if (containerServerPref == null || containerServerPref.getHost() == null) {
            throw new NoServerPrefException("No container server URI defined in preferences.");
        }
        return containerServerPref.toDto();
    }

    public void setServer(final String host) throws InvalidPreferenceName {
        setServer(host, null);
    }

    public DockerServer setServer(final String host, final String certPath) throws InvalidPreferenceName {
        containerServerPref.setHost(host);
        containerServerPref.setCertPath(certPath);
        return containerServerPref.toDto();
    }

    public DockerServer setServer(final DockerServer serverBean) throws InvalidPreferenceName {
        containerServerPref.setFromDto(serverBean);
        return serverBean;
    }

    @Override
    public String pingServer() throws NoServerPrefException, DockerServerException {
        try (final DockerClient client = getClient()) {
            return client.ping();
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
    }

    @Override
    public String pingHub(DockerHub hub) throws DockerServerException, NoServerPrefException {
        final AuthConfig authConfig = AuthConfig.builder()
                .email(hub.getEmail())
                .username(hub.getUsername())
                .password(hub.getPassword())
                .serverAddress(hub.getUrl())
                .build();
        try (final DockerClient client = getClient()) {
            client.pull("connectioncheckonly", authConfig);
        }
        catch (ImageNotFoundException imageNotFoundException){
            // Expected result: Hub found, bogus image not found
            return "OK";
        }
        catch (Exception e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
        return null;
    }

    /**
     * Query Docker server for all images
     *
     * @return Image objects stored on docker server
     **/
    @Override
    public List<DockerImage> getAllImages() throws NoServerPrefException, DockerServerException {
        return getImages(null);
    }

    /**
     * Query Docker server for images with parameters
     *
     * @param params Map of query parameters (name = value)
     * @return Image objects stored on docker server meeting the query parameters
     **/
    public List<DockerImage> getImages(final Map<String, String> params)
            throws NoServerPrefException, DockerServerException {
        return DockerImageToNrgImage(_getImages(params));
    }

    private List<com.spotify.docker.client.messages.Image> _getImages(final Map<String, String> params)
            throws NoServerPrefException, DockerServerException {
        // Transform param map to ListImagesParam array
        DockerClient.ListImagesParam[] dockerParams;
        if (params != null && params.size() > 0) {
            List<DockerClient.ListImagesParam> dockerParamsList =
                    Lists.transform(Lists.newArrayList(params.entrySet()), imageParamTransformer);
            dockerParams = dockerParamsList.toArray(new DockerClient.ListImagesParam[dockerParamsList.size()]);
        } else {
            dockerParams = new DockerClient.ListImagesParam[] {};
        }

        try (final DockerClient dockerClient = getClient()) {
            return dockerClient.listImages(dockerParams);
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to list images. " + e.getMessage());
            throw new DockerServerException(e);
        } catch (Error e) {
            log.error("Failed to list images. " + e.getMessage());
            throw e;
        }
    }

    /**
     * Query Docker server for image by name
     *
     * @param imageId ID of image
     * @return Image stored on docker server with the given name
     **/
    @Override
    public DockerImage getImageById(final String imageId)
        throws NotFoundException, DockerServerException, NoServerPrefException {
        final DockerImage image = DockerImageToNrgImage(_getImageById(imageId));
        if (image != null) {
            return image;
        }
        throw new NotFoundException(String.format("Could not find image %s", imageId));
    }

    private com.spotify.docker.client.messages.ImageInfo _getImageById(final String imageId)
        throws DockerServerException, NoServerPrefException {
        try (final DockerClient client = getClient()) {
            return client.inspectImage(imageId);
        } catch (DockerException | InterruptedException e) {
            throw new DockerServerException(e);
        }
    }

    /**
     * Launch image on Docker server
     *
     * @param command A ResolvedCommand. All templates are resolved, all mount paths exist.
     * @return ID of created Container
     **/
    @Override
    public String launchImage(final ResolvedCommand command)
            throws NoServerPrefException, DockerServerException {
        final String dockerImageId = command.getDockerImage();
        final String runCommand = command.getCommandLine();
        final List<String> bindMounts = Lists.newArrayList();
        for (final ContainerExecutionMount mount : command.getMountsIn()) {
            bindMounts.add(mount.toBindMountString());
        }
        for (final ContainerExecutionMount mount : command.getMountsOut()) {
            bindMounts.add(mount.toBindMountString());
        }
        final List<String> environmentVariables = Lists.newArrayList();
        for (final Map.Entry<String, String> env : command.getEnvironmentVariables().entrySet()) {
            environmentVariables.add(StringUtils.join(new String[] {env.getKey(), env.getValue()}, "="));
        }
        return launchImage(getServer(), dockerImageId, runCommand, bindMounts, environmentVariables, command.getPorts());
    }

//    /**
//     * Launch image on Docker server
//     *
//     * @param imageName name of image to launch
//     * @param runCommand Command string to execute
//     * @param volumes Volume mounts, in the form "/path/on/server:/path/in/container"
//     * @return ID of created Container
//     **/
//    @Override
//    public String launchImage(final String imageName, final List<String> runCommand, final List<String> volumes)
//            throws NoServerPrefException, DockerServerException {
//        return launchImage(getServer(), imageName, runCommand, volumes);
//    }

//    /**
//     * Launch image on Docker server
//     *
//     * @param server DockerServer on which to launch
//     * @param imageName name of image to launch
//     * @param runCommand Command string list to execute
//     * @param volumes Volume mounts, in the form "/path/on/server:/path/in/container"
//     * @return ID of created Container
//     **/
//    @Override
//    public String launchImage(final DockerServer server,
//                              final String imageName,
//                              final List<String> runCommand,
//                              final List<String> volumes) throws DockerServerException {
//        return launchImage(server, imageName, runCommand, volumes, null);
//    }
    /**
     * Launch image on Docker server
     *
     * @param server DockerServer on which to launch
     * @param imageName name of image to launch
     * @param runCommand Command string list to execute
     * @param volumes Volume mounts, in the form "/path/on/server:/path/in/container"
     * @return ID of created Container
     **/
    private String launchImage(final DockerServer server,
                               final String imageName,
                               final String runCommand,
                               final List<String> volumes,
                               final List<String> environmentVariables,
                               final Map<String, String> ports) throws DockerServerException {

        final Map<String, List<PortBinding>> portBindings = Maps.newHashMap();
        final List<String> portStringList = Lists.newArrayList();
        if (!(ports == null || ports.isEmpty())) {
            for (final Map.Entry<String, String> portEntry : ports.entrySet()) {
                final String containerPort = portEntry.getKey();
                final String hostPort = portEntry.getValue();

                if (StringUtils.isNotBlank(containerPort) && StringUtils.isNotBlank(hostPort)) {
                    final PortBinding portBinding = new PortBinding();
                    portBinding.hostPort(hostPort);
                    portBindings.put(containerPort + "/tcp", Lists.newArrayList(portBinding));

                    portStringList.add("host" + hostPort + "->" + "container" + containerPort);
                } else if (StringUtils.isNotBlank(containerPort)) {
                    // hostPort is blank
                    // TODO log it
                } else if (StringUtils.isNotBlank(hostPort)) {
                    // containerPort is blank
                    // TODO log it
                } else {
                    // both are blank
                    // TODO log it
                }
            }
        }

        final HostConfig hostConfig =
                HostConfig.builder()
                        .binds(volumes)
                        .portBindings(portBindings)
                        .build();
        final ContainerConfig containerConfig =
                ContainerConfig.builder()
                        .hostConfig(hostConfig)
                        .image(imageName)
                        .attachStdout(true)
                        .attachStderr(true)
                        .cmd(Lists.newArrayList("/bin/sh", "-c", runCommand))
                        .env(environmentVariables)
                        .build();

        if (log.isDebugEnabled()) {
            final String message = String.format(
                    "Starting container:" +
                            "\n\tserver %s" +
                            "\n\timage %s" +
                            "\n\tcommand \"%s\"" +
                            "\n\tvolumes [%s]" +
                            "\n\tenvironment variables [%s]" +
                            "\n\texposed ports: {%s}",
                    server,
                    imageName,
                    runCommand,
                    StringUtils.join(volumes, ", "),
                    StringUtils.join(environmentVariables, ", "),
                    StringUtils.join(portStringList, ", ")
            );
            log.debug(message);
        }

        try (final DockerClient client = getClient(server)) {
            final ContainerCreation container = client.createContainer(containerConfig);

            if (log.isDebugEnabled()) {
                log.debug("Starting container: id " + container.id());
            }
            if (container.getWarnings() != null) {
                for (String warning : container.getWarnings()) {
                    log.warn(warning);
                }
            }
            client.startContainer(container.id());

            return container.id();
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            log.error(e.getMessage());
            throw new DockerServerException("Could not start container from image " + imageName, e);
        }
    }

    @Override
    public void deleteImageById(final String id, final Boolean force) throws NoServerPrefException, DockerServerException {
        try (final DockerClient dockerClient = getClient()) {
            dockerClient.removeImage(id, force, false);
        } catch (DockerException|InterruptedException e) {
            throw new DockerServerException(e);
        }
    }

    /**
     * Pull image from default hub onto docker server
     *
     **/
    @Override
    public void pullImage(String name) throws NoServerPrefException, DockerServerException {
        try (final DockerClient client = getClient()) {
            client.pull(name);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
    }

    /**
     * Pull image from specified hub onto docker server
     *
     **/
    @Override
    public void pullImage(String name, DockerHub hub) throws NoServerPrefException, DockerServerException {
        if (hub == null) {
            pullImage(name);
        } else {
            try (final DockerClient client = getClient()) {
                final AuthConfig authConfig = AuthConfig.builder()
                        .email(hub.getEmail())
                        .username(hub.getUsername())
                        .password(hub.getPassword())
                        .serverAddress(hub.getUrl())
                        .build();
                client.pull(name, authConfig);
            } catch (DockerException | InterruptedException e) {
                log.error(e.getMessage());
                throw new DockerServerException(e);
            }
        }
    }

    @Override
    public DockerImage pullAndReturnImage(final String name) throws NoServerPrefException, DockerServerException {
        try (final DockerClient client = getClient()) {
            final LoadProgressHandler handler = new LoadProgressHandler();
            client.pull(name, handler);
            try {
                return getImageById(name);
            } catch (NotFoundException e) {
                final String m = String.format("The image %s was not found", name);
                log.error(m);
                throw new DockerServerException(m);
            }
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
    }

    @Override
    public DockerImage pullAndReturnImage(final String name, final DockerHub hub)
            throws NoServerPrefException, DockerServerException {
        if (hub == null) {
            return pullAndReturnImage(name);
        } else {
            try (final DockerClient client = getClient()) {
                final LoadProgressHandler handler = new LoadProgressHandler();
                final AuthConfig authConfig = AuthConfig.builder()
                        .email(hub.getEmail())
                        .username(hub.getUsername())
                        .password(hub.getPassword())
                        .serverAddress(hub.getUrl())
                        .build();
                client.pull(name, authConfig, handler);
                final String imageId = handler.getImageId();
                try {
                    return getImageById(imageId);
                } catch (NotFoundException e) {
                    final String m = String.format("The image with id %s was not found", imageId);
                    log.error(m);
                    throw new DockerServerException(m);
                }
            } catch (DockerException | InterruptedException e) {
                log.error(e.getMessage());
                throw new DockerServerException(e);
            }
        }
    }

    @Override
    public List<Command> parseLabels(final String imageId)
            throws DockerServerException, NoServerPrefException, NotFoundException {
        final DockerImage image = getImageById(imageId);
        return parseLabels(image);
    }

    @Override
    public List<Command> parseLabels(final DockerImage dockerImage) {
        final Map<String, String> labels = dockerImage.getLabels();
        if (labels != null && !labels.isEmpty() && labels.containsKey(LABEL_KEY)) {
            final String labelValue = labels.get(LABEL_KEY);
            if (StringUtils.isNotBlank(labelValue)) {
                try {
                    final List<Command> commands =
                            objectMapper.readValue(labelValue, new TypeReference<List<Command>>() {});
                    if (commands != null && !commands.isEmpty()) {
                        for (final Command command : commands) {
                            command.setDockerImage(dockerImage.getImageId());
                        }
                    }
                    return commands;
                } catch (IOException e) {
                    // TODO throw exception
                    log.info("Could not parse Commands from label: %s", labelValue);
                }
            }
        }
        return null;
    }

    /**
     * Query Docker server for all containers
     *
     * @return Container objects stored on docker server
     **/
    @Override
    public List<Container> getAllContainers() throws NoServerPrefException, DockerServerException {
        return getContainers(null);
    }

    /**
     * Query Docker server for containers with parameters
     *
     * @param params Map of query parameters (name = value)
     * @return Container objects stored on docker server meeting the query parameters
     **/
    @Override
    public List<Container> getContainers(final Map<String, String> params)
        throws NoServerPrefException, DockerServerException {
        List<com.spotify.docker.client.messages.Container> containerList;

        // Transform param map to ListImagesParam array
        DockerClient.ListContainersParam[] dockerParams;
        if (params != null && params.size() > 0) {
            List<DockerClient.ListContainersParam> dockerParamsList =
                    Lists.transform(Lists.newArrayList(params.entrySet()), containerParamTransformer);
            dockerParams = dockerParamsList.toArray(new DockerClient.ListContainersParam[dockerParamsList.size()]);
        } else {
            dockerParams = new DockerClient.ListContainersParam[] {};
        }

        try (final DockerClient dockerClient = getClient()) {
            containerList = dockerClient.listContainers(dockerParams);
        } catch (DockerException | InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
        return DockerContainerToNrgContainer(containerList);
    }

    /**
     * Query Docker server for specific container
     *
     * @param id Container ID
     * @return Container object with specified ID
     **/
    @Override
    public Container getContainer(final String id)
        throws NotFoundException, NoServerPrefException, DockerServerException {
        final Container container = DockerContainerToNrgContainer(_getContainer(id));
        if (container != null) {
            return container;
        }
        throw new NotFoundException(String.format("Could not find container %s", id));
    }

    private ContainerInfo _getContainer(final String id) throws NoServerPrefException, DockerServerException {
        final DockerClient client = getClient();
        try {
            return client.inspectContainer(id);
        } catch (DockerException | InterruptedException e) {
            log.error("Container server error." + e.getMessage());
            throw new DockerServerException(e);
        }
    }

    /**
     * Query Docker server for status of specific container
     *
     * @param id Container ID
     * @return Status of Container object with specified ID
     **/
    @Override
    public String getContainerStatus(final String id)
        throws NotFoundException, NoServerPrefException, DockerServerException {
        final Container container = getContainer(id);

        return container != null ? container.status() : null;
    }

    @Override
    public String getContainerStdoutLog(String id) throws NoServerPrefException, DockerServerException {
        try (final LogStream logStream = getClient().logs(id, LogsParam.stdout())) {
            return logStream.readFully();
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
    }

    @Override
    public String getContainerStderrLog(String id) throws NoServerPrefException, DockerServerException {
        try (final LogStream logStream = getClient().logs(id, LogsParam.stderr())) {
            return logStream.readFully();
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        }
    }

    /**
     * Create a client connection to a Docker server using default image repository configuration
     *
     * @return DockerClient object using default authConfig
     **/
    public DockerClient getClient() throws NoServerPrefException {
        return getClient(getServer());
    }

    /**
         * Create a client connection to a Docker server using default image repository configuration
         *
         * @return DockerClient object using default authConfig
         **/
    public DockerClient getClient(final DockerServer server) {
        if (log.isDebugEnabled()) {
            log.debug("method getClient, Create server connection, server " + server.getHost());
        }

        DefaultDockerClient.Builder clientBuilder =
            DefaultDockerClient.builder()
                .uri(server.getHost());

        if (StringUtils.isNotBlank(server.getCertPath())) {
            try {
                final DockerCertificates certificates =
                    new DockerCertificates(Paths.get(server.getCertPath()));
                clientBuilder = clientBuilder.dockerCertificates(certificates);
            } catch (DockerCertificateException e) {
                log.error("Could not find docker certificates at " + server.getCertPath(), e);
            }
        }

        return clientBuilder.build();
    }

    public DockerClient getClientFromEnv() throws DockerCertificateException {

        return DefaultDockerClient.fromEnv().build();
    }

    @Override
    public List<DockerContainerEvent> getContainerEvents(final Date since, final Date until) throws NoServerPrefException, DockerServerException {
        final List<Event> dockerEventList = getDockerContainerEvents(since, until);

        final List<DockerContainerEvent> events = Lists.newArrayList();
        for (final Event dockerEvent : dockerEventList) {
            final Event.Actor dockerEventActor = dockerEvent.actor();
            final DockerContainerEvent containerEvent =
                    new DockerContainerEvent(dockerEvent.action(),
                            dockerEventActor.id(),
                            dockerEvent.time(),
                            dockerEvent.timeNano(),
                            dockerEventActor.attributes());
            events.add(containerEvent);
        }
        return events;
    }

    @Override
    public List<DockerContainerEvent> getContainerEventsAndThrow(final Date since, final Date until) throws NoServerPrefException, DockerServerException {
        final List<DockerContainerEvent> events = getContainerEvents(since, until);

        for (final DockerContainerEvent event : events) {
            if (log.isDebugEnabled()) {
                log.debug("Throwing docker container event: " + event);
            }
            eventService.triggerEvent(event);
        }

        return events;
    }

    private List<Event> getDockerContainerEvents(final Date since, final Date until) throws NoServerPrefException, DockerServerException {
        try(final DockerClient client = getClient()) {
            if (log.isDebugEnabled()) {
                log.debug("Reading all docker container events from " + since.getTime() + " to " + until.getTime() + ".");
            }
            final List<Event> eventList;
            try (final EventStream eventStream =
                         client.events(since(since.getTime() / 1000),
                                 until((until.getTime() / 1000)),
                                 type(CONTAINER))) {

                if (log.isDebugEnabled()) {
                    log.debug("Got a stream of docker events.");
                }

                eventList = Lists.newArrayList(eventStream);
            }


            if (log.isDebugEnabled()) {
                log.debug("Closed docker event stream.");
            }

            return eventList;
        } catch (InterruptedException | DockerException e) {
            throw new DockerServerException(e);
        }
    }

    @Override
    public void killContainer(final String id) throws NoServerPrefException, DockerServerException, NotFoundException {
        try(final DockerClient client = getClient()) {
            log.info("Killing container " + id);
            client.killContainer(id);
        } catch (InterruptedException e) {
            log.error(e.getMessage());
            throw new DockerServerException(e);
        } catch (DockerException e) {
            if (e.getClass().isAssignableFrom(ContainerNotFoundException.class)) {
                log.error(e.getMessage());
                throw new NotFoundException(e);
            } else {
                log.error(e.getMessage());
                throw new DockerServerException(e);
            }
        }
    }

    // TODO Move everything below to a DAO class
    /**
     * Function to transform image query parameters from key/value to DockerClient.ListImagesParam
     **/
    private static Function<Map.Entry<String, String>, DockerClient.ListImagesParam> imageParamTransformer =
            new Function<Map.Entry<String, String>, DockerClient.ListImagesParam>() {
                @Override
                public DockerClient.ListImagesParam apply(Map.Entry<String, String> stringStringEntry) {
                    return new DockerClient.ListImagesParam(stringStringEntry.getKey(), stringStringEntry.getValue());
                }
            };

    /**
     * Function to transform container query parameters from key/value to DockerClient.ListContainersParam
     **/
    private static Function<Map.Entry<String, String>, DockerClient.ListContainersParam> containerParamTransformer =
            new Function<Map.Entry<String, String>, DockerClient.ListContainersParam>() {
                @Override
                public DockerClient.ListContainersParam apply(Map.Entry<String, String> stringStringEntry) {
                    return new DockerClient.ListContainersParam(stringStringEntry.getKey(), stringStringEntry.getValue());
                }
            };

    /**
     * Convert spotify-docker Image object to xnat-container Image object
     *
     * @param image Spotify-Docker Image object
     * @return NRG Image object
     **/
    private static DockerImage DockerImageToNrgImage(final Image image) {
        if (image == null) {
            return null;
        }

        return new DockerImage(image.id(), image.repoTags(), image.labels());
    }

    /**
     * Convert spotify-docker Image object to xnat-container Image object
     *
     * @param image Spotify-Docker Image object
     * @return NRG Image object
     **/
    private static DockerImage DockerImageToNrgImage(final ImageInfo image) {
        if (image == null) {
            return null;
        }

        return new DockerImage(image.id(), null, image.config().labels());
    }

    /**
     * Convert list of spotify-docker Image objects to list of xnat-container Image objects
     *
     * @param dockerImageList List of Spotify-Docker Image objects
     * @return List of NRG Image objects
     **/
    private static List<DockerImage> DockerImageToNrgImage(final List<Image> dockerImageList) {
        return Lists.transform(dockerImageList, DockerImageToNrgImage);
    }

    /**
     * Function to convert list of spotify-docker Image objects to list of xnat-container Image objects
     **/
    private static Function<Image, DockerImage> DockerImageToNrgImage =
            new Function<Image, DockerImage>() {
                @Override
                public DockerImage apply(final Image image) {
                    return DockerImageToNrgImage(image);
                }
            };

    /**
     * Convert spotify-docker Container object to xnat-container Container object
     *
     * @param dockerContainer Spotify-Docker Container object
     * @return NRG Container object
     **/
    private static Container DockerContainerToNrgContainer(final com.spotify.docker.client.messages.Container dockerContainer) {
        Container genericContainer = null;
        if (dockerContainer != null) {
            genericContainer =
                    new Container(dockerContainer.id(), dockerContainer.status());
        }
        return genericContainer;
    }

    /**
     * Convert spotify-docker Container object to xnat-container Container object
     *
     * @param dockerContainer Spotify-Docker ContainerInfo object
     * @return NRG Container object
     **/
    private static Container DockerContainerToNrgContainer(final com.spotify.docker.client.messages.ContainerInfo dockerContainer) {
        Container genericContainer = null;
        if (dockerContainer != null) {
            genericContainer =
                    new Container(
                            dockerContainer.id(),
                            dockerContainer.state().running() ? "Running" :
                                dockerContainer.state().paused() ? "Paused" :
                                dockerContainer.state().restarting() ? "Restarting" :
                                dockerContainer.state().exitCode() != null ? "Exited" :
                                null
                    );
        }
        return genericContainer;
    }

    /**
     * Convert list of spotify-docker Container objects to list of xnat-container Container objects
     *
     * @param dockerContainerList List of Spotify-Docker Container objects
     * @return List of NRG Container objects
     **/
    private static List<Container> DockerContainerToNrgContainer(final List<com.spotify.docker.client.messages.Container> dockerContainerList) {
        return Lists.transform(dockerContainerList, DockerContainerToNrgContainer);
    }

    /**
     * Function to convert list of spotify-docker Container objects to xnat-container Container objects
     **/
    private static Function<com.spotify.docker.client.messages.Container, Container> DockerContainerToNrgContainer =
            new Function<com.spotify.docker.client.messages.Container, Container>() {
                @Override
                public Container apply(com.spotify.docker.client.messages.Container container) {
                    return DockerContainerToNrgContainer(container);
                }
            };

    private static class LoadProgressHandler implements ProgressHandler {

        // The length of the image hash
        private static final int EXPECTED_CHARACTER_NUM1 = 64;
        // The length of the image digest
        private static final int EXPECTED_CHARACTER_NUM2 = 71;

        private final ProgressHandler delegate;

        private String imageId;

        private LoadProgressHandler() {
            this.delegate = new LoggingPullHandler("image stream");
        }

        private LoadProgressHandler(ProgressHandler delegate) {
            this.delegate = delegate;
        }

        private String getImageId() {
            Preconditions.checkState(imageId != null,
                    "Could not acquire image ID or digest following load");
            return imageId;
        }

        @Override
        public void progress(ProgressMessage message) throws DockerException {
            delegate.progress(message);
            final String status = message.status();
            if (status != null &&
                    (status.length() == EXPECTED_CHARACTER_NUM1 ||
                            status.length() == EXPECTED_CHARACTER_NUM2)) {
                imageId = message.status();
            }
        }

    }
}