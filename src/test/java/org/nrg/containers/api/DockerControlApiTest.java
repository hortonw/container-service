package org.nrg.containers.api;

import com.google.common.collect.Lists;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.nrg.containers.config.DockerControlApiTestConfig;
import org.nrg.containers.events.DockerContainerEvent;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.model.DockerHub;
import org.nrg.containers.model.DockerImage;
import org.nrg.containers.model.DockerServer;
import org.nrg.framework.scope.EntityId;
import org.nrg.prefs.services.NrgPreferenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = DockerControlApiTestConfig.class)
public class DockerControlApiTest {

    static String CONTAINER_HOST;
    static String CERT_PATH ;

    private static DockerClient client;

    private static final String BUSYBOX_LATEST = "busybox:latest";
    private static final String UBUNTU_LATEST = "ubuntu:latest";
    // private static final String KELSEYM_PYDICOM = "kelseym/pydicom:latest";
    private static final String BUSYBOX_ID = "sha256:47bcc53f74dc94b1920f0b34f6036096526296767650f223433fe65c35f149eb";
    private static final String BUSYBOX_NAME = "busybox:1.24.2-uclibc";

    @Autowired
    private DockerControlApi controlApi;

    @Autowired
    private NrgPreferenceService mockPrefsService;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void setup() throws Exception {

        final String defaultHost = "unix:///var/run/docker.sock";
        final String hostEnv = System.getenv("DOCKER_HOST");
        CONTAINER_HOST = StringUtils.isBlank(hostEnv) ? defaultHost : hostEnv;

        final String tlsVerify = System.getenv("DOCKER_TLS_VERIFY");
        final String certPathEnv = System.getenv("DOCKER_CERT_PATH");
        if (tlsVerify != null && tlsVerify.equals("1")) {
            if (StringUtils.isBlank(certPathEnv)) {
                throw new Exception("Must set DOCKER_CERT_PATH if DOCKER_TLS_VERIFY=1.");
            }
            CERT_PATH = certPathEnv;
        } else {
            CERT_PATH = "";
        }

        final Date timeZero = new Date(0L);
        final String timeZeroString = String.valueOf(timeZero.getTime());

        // Set up mock prefs service for all the calls that will initialize
        // the ContainerServerPrefsBean
        when(mockPrefsService.getPreferenceValue("docker-server", "host"))
            .thenReturn(CONTAINER_HOST);
        when(mockPrefsService.getPreferenceValue("docker-server", "certPath"))
            .thenReturn(CERT_PATH);
        when(mockPrefsService.getPreferenceValue("docker-server", "lastEventCheckTime", EntityId.Default.getScope(), EntityId.Default.getEntityId()))
            .thenReturn(timeZeroString);
        doNothing().when(mockPrefsService)
            .setPreferenceValue("docker-server", "host", "");
        doNothing().when(mockPrefsService)
            .setPreferenceValue("docker-server", "certPath", "");
        doNothing().when(mockPrefsService)
            .setPreferenceValue("docker-server", "lastEventCheckTime", "");
        when(mockPrefsService.hasPreference("docker-server", "host"))
            .thenReturn(true);
        when(mockPrefsService.hasPreference("docker-server", "certPath"))
            .thenReturn(true);
        when(mockPrefsService.hasPreference("docker-server", "lastEventCheckTime"))
            .thenReturn(true);

        client = controlApi.getClient();
    }

    @After
    public void tearDown() throws Exception {
        return;
    }

    @Test
    public void testGetServer() throws Exception {
        final DockerServer server = controlApi.getServer();
        assertEquals(CONTAINER_HOST, server.getHost());
        assertEquals(CERT_PATH, server.getCertPath());
    }

    @Test
    public void testGetAllImages() throws Exception {
        client.pull(BUSYBOX_LATEST);
        client.pull(UBUNTU_LATEST);
        final List<DockerImage> images = controlApi.getAllImages();

        final List<String> imageNames = imagesToTags(images);
        assertThat(BUSYBOX_LATEST, isIn(imageNames));
        assertThat(UBUNTU_LATEST, isIn(imageNames));
    }

    private List<String> imagesToTags(final List<DockerImage> images) {
        final List<String> tags = Lists.newArrayList();
        for (final DockerImage image : images) {
            if (image.getTags() != null) {
                tags.addAll(image.getTags());
            }
        }
        return tags;
    }

//    @Test
//    public void testLaunchImage() throws Exception {
//        final List<String> cmd = Lists.newArrayList("ls", "/data/pyscript.py");
//        final List<String> vol =
//            Lists.newArrayList("/Users/Kelsey/Projects/XNAT/1.7/pydicomDocker/data:/data");
//
//        client.pull(KELSEYM_PYDICOM);
//        String containerId = controlApi.launchImage(KELSEYM_PYDICOM, cmd, vol);
//        assertThat(containerId, not(isEmptyOrNullString()));
//    }

//    @Test
//    public void testLaunchPythonScript() throws Exception {
//       // python pyscript.py -h <hostname> -u <user> -p <password> -s <session_id>
//        final List<String> cmd = Lists.newArrayList(
//            "python", "/data/pyscript.py",
//            "-h", "https://central.xnat.org",
//            "-u", "admin",
//            "-p", "admin",
//            "-s", "CENTRAL_E07096"
//        );
//        final List<String> vol =
//            Lists.newArrayList("/Users/Kelsey/Projects/XNAT/1.7/pydicomDocker/data:/data");
//
//        client.pull(KELSEYM_PYDICOM);
//        String containerId = controlApi.launchImage(KELSEYM_PYDICOM, cmd, vol);
//    }

    @Test
    public void testPingServer() throws Exception {
        assertEquals("OK", controlApi.pingServer());
    }

    @Test
    public void testPingHub() throws Exception {
        final DockerHub dockerHub = new DockerHub();
        dockerHub.setUrl("https://index.docker.io/v1/");
        dockerHub.setName("Docker Hub");

        assertEquals("OK", controlApi.pingHub(dockerHub));
    }

    @Test
    public void testPullImage() throws Exception {
        controlApi.pullImage(BUSYBOX_LATEST);

    }

    @Test
    public void testDeleteImage() throws DockerException, InterruptedException, NoServerPrefException, DockerServerException {
        client.pull(BUSYBOX_NAME);
        int beforeImageCount = client.listImages().size();
        controlApi.deleteImageById(BUSYBOX_ID, true);
        List<com.spotify.docker.client.messages.Image> images = client.listImages();
        int afterImageCount = images.size();
        assertEquals(beforeImageCount, afterImageCount+1);
        for(com.spotify.docker.client.messages.Image image:images){
            assertNotEquals(BUSYBOX_ID, image.id());
        }
    }

    @Test(timeout = 10000)
    public void testEventPolling() throws Exception {
        final Date start = new Date();
        Thread.sleep(1000); // Wait to ensure we get some events

        controlApi.pullImage(BUSYBOX_LATEST);

        // Create container, to ensure we have some events to read
        final ContainerConfig config = ContainerConfig.builder()
                .image(BUSYBOX_LATEST)
                .cmd("sh", "-c", "echo Hello world")
                .build();

        final ContainerCreation creation = client.createContainer(config);
        client.startContainer(creation.id());

        Thread.sleep(1000); // Wait to ensure we get some events
        final Date end = new Date();

        final List<DockerContainerEvent> events = controlApi.getContainerEvents(start, end);

        // The fact that we have a list of events and not a timeout failure is already a victory
        assertThat(events, not(empty()));

        // TODO assert more things about the events
    }

}


