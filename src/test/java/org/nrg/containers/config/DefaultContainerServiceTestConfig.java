package org.nrg.containers.config;

import org.mockito.Mockito;
import org.nrg.automation.services.ScriptService;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.model.ContainerServerPrefsBean;
import org.nrg.containers.model.ContainerHubPrefs;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.impl.DefaultContainerService;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.transporter.TransportService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@EnableWebMvc
public class DefaultContainerServiceTestConfig {
    @Bean
    public ContainerControlApi mockContainerControlApi() {
        return Mockito.mock(ContainerControlApi.class);
    }

    @Bean
    public ScriptService mockScriptService() {
        return Mockito.mock(ScriptService.class);
    }

    @Bean
    public ContainerServerPrefsBean mockContainerServer() {
        return Mockito.mock(ContainerServerPrefsBean.class);
    }

    @Bean
    public ContainerHubPrefs containerHubPrefs() {
        return new ContainerHubPrefs();
    }

    @Bean
    public TransportService mockTransportService() {
        return Mockito.mock(TransportService.class);
    }

    @Bean
    public NrgPreferenceService mockPrefsService() {
        return Mockito.mock(NrgPreferenceService.class);
    }

    @Bean
    public ContainerService containerService() {
        return new DefaultContainerService();
    }
}
