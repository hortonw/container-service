package org.nrg.containers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.containers.config.IntegrationTestConfig;
import org.nrg.containers.model.Command;
import org.nrg.containers.model.CommandMount;
import org.nrg.containers.model.CommandOutput;
import org.nrg.containers.model.ContainerExecution;
import org.nrg.containers.model.ContainerExecutionMount;
import org.nrg.containers.model.ContainerExecutionOutput;
import org.nrg.containers.model.DockerServerPrefsBean;
import org.nrg.containers.model.xnat.Project;
import org.nrg.containers.model.xnat.Resource;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.services.CommandService;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = IntegrationTestConfig.class)
public class CommandLaunchIntegrationTest {
    private UserI mockUser;

    @Autowired private ObjectMapper mapper;
    @Autowired private CommandService commandService;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private DockerServerPrefsBean mockDockerServerPrefsBean;
    @Autowired private SiteConfigPreferences mockSiteConfigPreferences;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    @Rule public TemporaryFolder folder = new TemporaryFolder(new File("/tmp"));

    @Before
    public void setup() throws Exception {
        Configuration.setDefaults(new Configuration.Defaults() {

            private final JsonProvider jsonProvider = new JacksonJsonProvider();
            private final MappingProvider mappingProvider = new JacksonMappingProvider();

            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }

            @Override
            public Set<Option> options() {
                return Sets.newHashSet(Option.DEFAULT_PATH_LEAF_TO_NULL);
            }
        });

        // Mock out the prefs bean
        final String containerHost = "unix:///var/run/docker.sock";
        when(mockDockerServerPrefsBean.getHost()).thenReturn(containerHost);
        when(mockDockerServerPrefsBean.toDto()).thenCallRealMethod();

        // Mock the userI
        mockUser = Mockito.mock(UserI.class);
        when(mockUser.getLogin()).thenReturn("mockUser");

        // Mock the user management service
        when(mockUserManagementServiceI.getUser("mockUser")).thenReturn(mockUser);

        // Mock the aliasTokenService
        final AliasToken mockAliasToken = new AliasToken();
        mockAliasToken.setAlias("alias");
        mockAliasToken.setSecret("secret");
        when(mockAliasTokenService.issueTokenForUser(mockUser)).thenReturn(mockAliasToken);

        // Mock the site config preferences
        when(mockSiteConfigPreferences.getSiteUrl()).thenReturn("mock://url");
        when(mockSiteConfigPreferences.getBuildPath()).thenReturn(folder.newFolder().getAbsolutePath()); // transporter makes a directory under build
        when(mockSiteConfigPreferences.getArchivePath()).thenReturn(folder.newFolder().getAbsolutePath()); // container logs get stored under archive
    }

    @Test
    public void testFakeReconAll() throws Exception {
        final String dir = Resources.getResource("commandLaunchTest").getPath();
        final String commandJsonFile = dir + "/fakeReconAllCommand.json";
        final String sessionJsonFile = dir + "/session.json";
        final String fakeResourceDir = dir + "/fakeResource";

        final Command fakeReconAll = mapper.readValue(new File(commandJsonFile), Command.class);
        commandService.create(fakeReconAll);
        commandService.flush();

        final Session session = mapper.readValue(new File(sessionJsonFile), Session.class);
        final Scan scan = session.getScans().get(0);
        final Resource resource = scan.getResources().get(0);
        resource.setDirectory(fakeResourceDir);
        final String sessionJson = mapper.writeValueAsString(session);

        final String t1Scantype = "T1_TEST_SCANTYPE";

        final Map<String, String> runtimeValues = Maps.newHashMap();
        runtimeValues.put("session", sessionJson);
        runtimeValues.put("T1-scantype", t1Scantype);

        final ContainerExecution execution = commandService.resolveAndLaunchCommand(fakeReconAll.getId(), runtimeValues, mockUser);
        Thread.sleep(1000); // Wait for container to finish

        final Map<String, String> inputValues = Maps.newHashMap(execution.getInputValues());
        assertEquals(
                Sets.newHashSet("session", "label", "T1-scantype", "T1", "resource", "other-recon-all-args"),
                inputValues.keySet());
        assertEquals(session, mapper.readValue(inputValues.get("session"), Session.class));
        assertEquals(session.getLabel(), inputValues.get("label"));
        assertEquals(t1Scantype, inputValues.get("T1-scantype"));
        assertEquals(scan, mapper.readValue(inputValues.get("T1"), Scan.class));
        assertEquals(resource, mapper.readValue(inputValues.get("resource"), Resource.class));
        assertEquals("-all", inputValues.get("other-recon-all-args"));

        final List<ContainerExecutionOutput> outputs = execution.getOutputs();
        assertEquals(Lists.newArrayList("fs", "data"), Lists.transform(outputs, new Function<ContainerExecutionOutput, String>() {
            @Nullable
            @Override
            public String apply(@Nullable final ContainerExecutionOutput output) {
                return output == null ? "" : output.getName();
            }
        }));

        assertThat(execution.getMountsOut(), hasSize(1));
        final ContainerExecutionMount mountOut = execution.getMountsOut().get(0);
        final String outputPath = mountOut.getHostPath();
        final File outputFile = new File(outputPath + "/out.txt");
        if (!outputFile.canRead()) {
            fail("Cannot read output file " + outputFile.getAbsolutePath());
        }
        final String[] outputFileContents = FileUtils.readFileToString(outputFile).split("\\n");
        assertThat(outputFileContents.length, greaterThanOrEqualTo(2));
        assertEquals("recon-all -s session1 -all", outputFileContents[0]);

        final File fakeResourceDirFile = new File(fakeResourceDir);
        assertNotNull(fakeResourceDirFile);
        assertNotNull(fakeResourceDirFile.listFiles());
        final List<String> fakeResourceDirFileNames = Lists.newArrayList();
        for (final File file : fakeResourceDirFile.listFiles()) {
            fakeResourceDirFileNames.add(file.getName());

        }
        assertEquals(fakeResourceDirFileNames, Lists.newArrayList(outputFileContents[1].split(" ")));
    }
}
