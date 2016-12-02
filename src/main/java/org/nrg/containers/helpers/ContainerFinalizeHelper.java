package org.nrg.containers.helpers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.model.ContainerExecution;
import org.nrg.containers.model.ContainerExecutionMount;
import org.nrg.containers.model.ContainerExecutionOutput;
import org.nrg.containers.model.xnat.XnatModelObject;
import org.nrg.transporter.TransportService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xnat.restlet.util.XNATRestConstants;
import org.nrg.xnat.services.archive.CatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class ContainerFinalizeHelper {
    private static final Logger log = LoggerFactory.getLogger(ContainerFinalizeHelper.class);

    private ContainerControlApi containerControlApi;
    private SiteConfigPreferences siteConfigPreferences;
    private TransportService transportService;
    private PermissionsServiceI permissionsService;
    private CatalogService catalogService;
    private ObjectMapper mapper;

    private ContainerExecution containerExecution;
    private UserI userI;
    private String exitCode;

    private Map<String, ContainerExecutionMount> untransportedMounts;
    private Map<String, ContainerExecutionMount> transportedMounts;
    private Map<String, String> inputUriCache;

    private ContainerFinalizeHelper(final ContainerExecution containerExecution,
                                    final UserI userI,
                                    final String exitCode,
                                    final ContainerControlApi containerControlApi,
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

        this.containerExecution = containerExecution;
        this.userI = userI;
        this.exitCode = exitCode;

        untransportedMounts = Maps.newHashMap();
        transportedMounts = Maps.newHashMap();
        inputUriCache = Maps.newHashMap();
    }

    public static void finalizeContainer(final ContainerExecution containerExecution,
                                         final UserI userI,
                                         final String exitCode,
                                         final ContainerControlApi containerControlApi,
                                         final SiteConfigPreferences siteConfigPreferences,
                                         final TransportService transportService,
                                         final PermissionsServiceI permissionsService,
                                         final CatalogService catalogService,
                                         final ObjectMapper mapper) {
        final ContainerFinalizeHelper helper =
                new ContainerFinalizeHelper(containerExecution, userI, exitCode, containerControlApi, siteConfigPreferences, transportService, permissionsService, catalogService, mapper);
        helper.finalizeContainer();
    }

    private void finalizeContainer() {
        uploadLogs();

        // TODO Add some stuff with status code. "x" means "don't know", "0" success, greater than 0 failure.

        if (containerExecution.getOutputs() != null) {
            if (containerExecution.getMountsOut() != null) {
                for (final ContainerExecutionMount mountOut : containerExecution.getMountsOut()) {
                    untransportedMounts.put(mountOut.getName(), mountOut);
                }
            }

            uploadOutputs();
        }
    }

    private void uploadLogs() {
        log.info("Getting container logs");

        String stdoutLogStr = "";
        String stderrLogStr = "";
        try {
            stdoutLogStr = containerControlApi.getContainerStdoutLog(containerExecution.getContainerId());
            stderrLogStr = containerControlApi.getContainerStderrLog(containerExecution.getContainerId());
        } catch (DockerServerException | NoServerPrefException e) {
            log.error("Could not get container logs for container with id " + containerExecution.getContainerId(), e);
        }

        if (StringUtils.isNotBlank(stdoutLogStr) || StringUtils.isNotBlank(stderrLogStr)) {

            final String archivePath = siteConfigPreferences.getArchivePath(); // TODO find a place to upload this thing. Root of the archive if sitewide, else under the archive path of the root object
            if (StringUtils.isNotBlank(archivePath)) {
                                final SimpleDateFormat formatter = new SimpleDateFormat(XNATRestConstants.PREARCHIVE_TIMESTAMP);
                final String datestamp = formatter.format(new Date());
                final String containerExecPath = FileUtils.AppendRootPath(archivePath, "CONTAINER_EXEC/");
                final String destinationPath = containerExecPath + datestamp + "/LOGS/";
                final File destination = new File(destinationPath);
                destination.mkdirs();

                log.info("Saving container logs to " + destinationPath);

                if (StringUtils.isNotBlank(stdoutLogStr)) {
                    final File stdoutFile = new File(destination, "stdout.log");
                    FileUtils.OutputToFile(stdoutLogStr, stdoutFile.getAbsolutePath());
                }

                if (StringUtils.isNotBlank(stderrLogStr)) {
                    final File stderrFile = new File(destination, "stderr.log");
                    FileUtils.OutputToFile(stderrLogStr, stderrFile.getAbsolutePath());
                }
            }
        }
    }

    private void uploadOutputs() {
        log.info("Uploading command outputs.");


        for (final ContainerExecutionOutput output: containerExecution.getOutputs()) {
            try {
                uploadOutput(output);
            } catch (ContainerException | RuntimeException e) {
                log.error("Cannot upload files for command output " + output.getName(), e);
            }
        }

        log.info("Done uploading command outputs.");
    }

    private void uploadOutput(final ContainerExecutionOutput output) throws ContainerException {
        if (log.isInfoEnabled()) {
            log.info(String.format("Uploading command output \"%s\".", output.getName()));
        }
        if (log.isDebugEnabled()) {
            log.debug(output.toString());
        }

        final String mountName = output.getMount();
        final String relativeFilePath = output.getPath() != null ? output.getPath() : "";
        final ContainerExecutionMount mount = getMount(mountName);
        if (mount == null) {
            throw new ContainerException(String.format("Mount \"%s\" does not exist.", mountName));
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format("Output files are provided by mount \"%s\": %s", mount.getName(), mount));
        }

        if (StringUtils.isBlank(mount.getHostPath())) {
            throw new ContainerException(String.format("Cannot upload output \"%s\". Mount \"%s\" has blank hostPath.", output.getName(), mount.getName()));
        }

        final List<File> toUpload;
        if (StringUtils.isBlank(relativeFilePath)) {
            // This is fine. It just means upload everything in the build directory.
            final File buildDir = new File(mount.getHostPath());
            final File[] buildDirContents = buildDir.listFiles();
            if (buildDirContents == null || buildDirContents.length == 0) {
                throw new ContainerException(String.format("Nothing to upload for output \"%s\". Mount \"%s\" hostPath has no files.", output.getName(), mount.getName()));
            }
            toUpload = Arrays.asList(buildDirContents);
        } else {
            final String filePath = FilenameUtils.concat(mount.getHostPath(), relativeFilePath);
            if (StringUtils.isBlank(filePath)) {
                // This shouldn't happen. We know mount.getHostPath() and relativeFilePath are non-blank
                throw new ContainerException(String.format("Cannot upload output \"%s\". Mount \"%s\" hostPath + output path is blank.", output.getName(), mount.getName()));
            }

            toUpload = Lists.newArrayList(new File(filePath));
        }

        final String label = StringUtils.isNotBlank(output.getLabel()) ? output.getLabel() :
                StringUtils.isNotBlank(mount.getResource()) ? mount.getResource() :
                        mountName;

        final String parentInputUri = getInputUri(output.getParentInputName());
        if (StringUtils.isBlank(parentInputUri)) {
            throw new ContainerException(String.format("Cannot upload output \"%s\". Parent \"%s\" URI is blank.", output.getName(), output.getParentInputName()));
        }

        switch (output.getType()) {
            case RESOURCE:
                if (log.isDebugEnabled()) {
                    final String template = "Inserting file resource.\n\tuser: %s\n\tparentInputUri: %s\n\tlabel: %s\n\ttoUpload: %s";
                    log.debug(String.format(template, userI.getLogin(), parentInputUri, label, toUpload));
                }
                try {
                    catalogService.insertResources(userI, "/archive" + parentInputUri, toUpload, label, null, null, null);
                } catch (Exception e) {
                    throw new ContainerException("Could not upload files to resource.", e);
                }
                break;
            case ASSESSOR:
                /* TODO Waiting on XNAT-4556
                final CommandMount mount = getMount(output.getFiles().getMount());
                final String absoluteFilePath = FilenameUtils.concat(mount.getHostPath(), output.getFiles().getPath());
                final SAXReader reader = new SAXReader(userI);
                XFTItem item = null;
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Reading XML file at " + absoluteFilePath);
                    }
                    item = reader.parse(new File(absoluteFilePath));

                } catch (IOException e) {
                    log.error("An error occurred reading the XML", e);
                } catch (SAXException e) {
                    log.error("An error occurred parsing the XML", e);
                }

                if (!reader.assertValid()) {
                    throw new ContainerException("XML file invalid", reader.getErrors().get(0));
                }
                if (item == null) {
                    throw new ContainerException("Could not create assessor from XML");
                }

                try {
                    if (item.instanceOf("xnat:imageAssessorData")) {
                        final XnatImageassessordata assessor = (XnatImageassessordata) BaseElement.GetGeneratedItem(item);
                        if(permissionsService.canCreate(userI, assessor)){
                            throw new ContainerException(String.format("User \"%s\" has insufficient privileges for assessors in project \"%s\".", userI.getLogin(), assessor.getProject()));
                        }

                        if(assessor.getLabel()==null){
                            assessor.setLabel(assessor.getId());
                        }

                        // I hate this
                    }
                } catch (ElementNotFoundException e) {
                    throw new ContainerException(e);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                 */
                break;
        }
    }

    private ContainerExecutionMount getMount(final String mountName) throws ContainerException {
        // If mount has been transported, we're done
        if (transportedMounts.containsKey(mountName)) {
            return transportedMounts.get(mountName);
        }

        // If mount exists but has not been transported, transport it
        if (untransportedMounts.containsKey(mountName)) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Transporting mount \"%s\".", mountName));
            }
            final ContainerExecutionMount mountToTransport = untransportedMounts.get(mountName);
            final Path pathOnExecutionMachine = Paths.get(mountToTransport.getHostPath());
            final Path pathOnXnatMachine = transportService.transport("", pathOnExecutionMachine); // TODO this currently does nothing
            mountToTransport.setHostPath(pathOnXnatMachine.toAbsolutePath().toString());

            transportedMounts.put(mountName, mountToTransport);
            untransportedMounts.remove(mountName);

            log.debug("Done transporting mount.");
            return mountToTransport;
        }

        // Mount does not exist
        throw new ContainerException(String.format("Mount \"%s\" does not exist.", mountName));
    }

    private String getInputUri(final String inputName) {
        if (log.isDebugEnabled()) {
            log.debug(String.format("Getting URI for input \"%s\".", inputName));
        }
        if (inputUriCache.containsKey(inputName)) {
            if (log.isDebugEnabled()) {
                log.debug("Input URI was cached. Value: " + inputUriCache.get(inputName));
            }
            return inputUriCache.get(inputName);
        }

        final Map<String, String> inputValues = containerExecution.getInputValues();
        if (!inputValues.containsKey(inputName)) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("No input found with name \"%s\". Input name set: %s", inputName, inputValues.keySet()));
            }
            return null;
        }

        final String parentInputValue = containerExecution.getInputValues().get(inputName);
        String parentUri = "";
        try {
            final XnatModelObject parent = mapper.readValue(parentInputValue, XnatModelObject.class);
            if (log.isDebugEnabled()) {
                log.debug(String.format("Deserialized input \"%s\": %s", inputName, parent));
            }
            parentUri = parent.getUri();
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                // Yes, I know I checked for "debug" and am logging at "error".
                // I still want this to show up as "error" either way, but I only want the full object to
                // be logged if you opted into the firehose.
                log.error("Could not deserialize Container Execution input value:\n" + parentInputValue, e);
            } else {
                log.error("Could not deserialize Container Execution input value.", e);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format("Caching URI for input \"%s\": %s", inputName, parentUri));
        }
        inputUriCache.put(inputName, parentUri);
        return parentUri;
    }
}
