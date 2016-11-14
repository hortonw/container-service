package org.nrg.containers.rest;

import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.model.ContainerExecution;
import org.nrg.containers.services.ContainerExecutionService;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.rest.AbstractXapiRestController;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@XapiRestController
@RequestMapping(value = "/containerexecutions")
public class ContainerExecutionRestApi extends AbstractXapiRestController {
    private static final String JSON = MediaType.APPLICATION_JSON_UTF8_VALUE;

    private ContainerExecutionService containerExecutionService;

    @Autowired
    public ContainerExecutionRestApi(final ContainerExecutionService containerExecutionService,
                                     final UserManagementServiceI userManagementService,
                                     final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.containerExecutionService = containerExecutionService;
    }

    @RequestMapping(method = GET)
    @ResponseBody
    public List<ContainerExecution> getAll() {
        return containerExecutionService.getAll();
    }

    @RequestMapping(value = "/{id}", method = GET)
    @ResponseBody
    public ContainerExecution getOne(final @PathVariable Long id) throws NotFoundException {
        final ContainerExecution containerExecution = containerExecutionService.retrieve(id);
        if (containerExecution == null) {
            throw new NotFoundException("ContainerExecution " + id + " not found.");
        }
        return containerExecution;
    }

    @RequestMapping(value = "/{id}", method = DELETE)
    public ResponseEntity<String> delete(final @PathVariable Long id) {
        containerExecutionService.delete(id);
        return new ResponseEntity<>("", HttpStatus.NO_CONTENT);
    }

    @RequestMapping(value = "/{id}/finalize", method = POST, produces = JSON)
    public void finalize(final @PathVariable Long id) throws NotFoundException {
        final UserI userI = XDAT.getUserDetails();
        containerExecutionService.finalize(id, userI);
    }
}
