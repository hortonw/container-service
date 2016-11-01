package org.nrg.containers.helpers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.mapper.MappingException;
import org.apache.commons.lang3.StringUtils;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.exceptions.CommandInputResolutionException;
import org.nrg.containers.exceptions.CommandMountResolutionException;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.model.Command;
import org.nrg.containers.model.CommandInput;
import org.nrg.containers.model.CommandMount;
import org.nrg.containers.model.CommandOutput;
import org.nrg.containers.model.CommandOutputFiles;
import org.nrg.containers.model.ResolvedCommand;
import org.nrg.containers.model.xnat.Resource;
import org.nrg.containers.model.xnat.Scan;
import org.nrg.containers.model.xnat.Session;
import org.nrg.containers.model.xnat.XnatFile;
import org.nrg.containers.model.xnat.XnatModelObject;
import org.nrg.framework.constants.Scope;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xft.security.UserI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandResolutionHelper {
    private static final Logger log = LoggerFactory.getLogger(CommandResolutionHelper.class);
    private static final String JSONPATH_SUBSTRING_REGEX = "\\^(.+)\\^";

    private Command command;
    private ResolvedCommand resolvedCommand;
    private Command cachedCommand;
    private String commandJson;
    private Map<String, CommandInput> resolvedInputObjects;
    private Map<String, String> resolvedInputValuesByReplacementKey;
    private Map<String, String> resolvedInputCommandLineValuesByReplacementKey;
    private UserI userI;
    private ObjectMapper mapper;
    private Map<String, String> inputValues;
    private ConfigService configService;
    private Pattern jsonpathSubstringPattern;

    private CommandResolutionHelper(final Command command,
                                    final Map<String, String> inputValues,
                                    final UserI userI,
                                    final ConfigService configService) {
        this.command = command;
        resolvedCommand = new ResolvedCommand(command);
        this.cachedCommand = null;
        this.commandJson = null;
        this.resolvedInputObjects = Maps.newHashMap();
        this.resolvedInputValuesByReplacementKey = Maps.newHashMap();
        this.resolvedInputCommandLineValuesByReplacementKey = Maps.newHashMap();
//            command.setInputs(Lists.<CommandInput>newArrayList());
        this.userI = userI;
        this.mapper = new ObjectMapper();
        this.inputValues = inputValues == null ?
                Maps.<String, String>newHashMap() :
                inputValues;
        this.configService = configService;
        this.jsonpathSubstringPattern = Pattern.compile(JSONPATH_SUBSTRING_REGEX);
    }

    public static ResolvedCommand resolve(final Command command,
                                          final Map<String, String> inputValues,
                                          final UserI userI,
                                          final ConfigService configService)
            throws CommandResolutionException {
        final CommandResolutionHelper helper = new CommandResolutionHelper(command, inputValues, userI, configService);
        return helper.resolve();
    }

    private ResolvedCommand resolve() throws CommandResolutionException {
        if (log.isDebugEnabled()) {
            log.debug("Resolving command " + command);
        }

        resolvedCommand.setInputValues(resolveInputs());
        resolvedCommand.setOutputs(resolveOutputs());
        resolvedCommand.setCommandLine(resolveCommandLine());
        resolvedCommand.setMounts(resolveCommandMounts());
        resolvedCommand.setEnvironmentVariables(resolveEnvironmentVariables());

        return resolvedCommand;
    }

    private Map<String, String> resolveInputs() throws CommandResolutionException {
        if (log.isDebugEnabled()) {
            log.debug("Resolving command inputs");
        }
        if (command.getInputs() == null) {
            return null;
        }

        final Map<String, String> resolvedInputValuesByName = Maps.newHashMap();
        for (final CommandInput input : command.getInputs()) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Resolving input \"%s\"", input.getName()));
            }

            // Check that all prerequisites have already been resolved.
            // TODO Move this to a command validation function. Command should not be saved unless inputs are in correct order. At this stage, we should be able to safely iterate.
            final List<String> prerequisites = StringUtils.isNotBlank(input.getPrerequisites()) ?
                    Lists.newArrayList(input.getPrerequisites().split("\\s*,\\s*")) :
                    Lists.<String>newArrayList();
            if (StringUtils.isNotBlank(input.getParent()) && !prerequisites.contains(input.getParent())) {
                // Parent is always a prerequisite
                prerequisites.add(input.getParent());
            }

            if (log.isDebugEnabled()) {
                log.debug("Prerequisites: " + prerequisites.toString());
            }
            for (final String prereq : prerequisites) {
                if (!resolvedInputObjects.containsKey(prereq)) {
                    final String message = String.format(
                            "Input %1$s has prerequisite %2$s which has not been resolved. Re-order inputs so %1$s appears after %2$s.",
                            input.getName(), prereq
                    );
                    throw new CommandInputResolutionException(message, input);
                }
            }

            // If input requires a parent, it must be resolved first
            CommandInput parent = null;
            if (StringUtils.isNotBlank(input.getParent())) {
                if (resolvedInputObjects.containsKey(input.getParent())) {
                    // Parent has already been resolved. We can continue.
                    parent = resolvedInputObjects.get(input.getParent());
                } else {
                    // This exception should have been thrown already above, but just in case it wasn't...
                    final String message = String.format(
                            "Input %1$s has prerequisite %2$s which has not been resolved. Re-order inputs so %1$s appears after %2$s.",
                            input.getName(), input.getParent()
                    );
                    throw new CommandInputResolutionException(message, input);
                }
            }

            String resolvedValue = null;

            // Give the input its default value
            if (log.isDebugEnabled()) {
                log.debug("Default value: " + input.getDefaultValue());
            }
            if (input.getDefaultValue() != null) {
                 resolvedValue = resolveJsonpathSubstring(input.getDefaultValue());
            }

            // If a value was provided at runtime, use that over the default
            if (inputValues.containsKey(input.getName()) && inputValues.get(input.getName()) != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Runtime value: " + inputValues.get(input.getName()));
                }
                resolvedValue = resolveJsonpathSubstring(inputValues.get(input.getName()));
            }

            if (log.isDebugEnabled()) {
                log.debug("Matcher: " + input.getMatcher());
            }
            final String resolvedMatcher = input.getMatcher() != null ? resolveJsonpathSubstring(input.getMatcher()) : null;

            if (log.isDebugEnabled()) {
                log.debug("Processing input value as a " + input.getType());
            }
            switch (input.getType()) {
                case BOOLEAN:
                    // Parse the value as a boolean, and use the trueValue/falseValue
                    // If those haven't been set, just pass the value through
                    if (Boolean.parseBoolean(resolvedValue)) {
                        resolvedValue = input.getTrueValue() != null ? input.getTrueValue() : resolvedValue;
                    } else {
                        resolvedValue = input.getFalseValue() != null ? input.getFalseValue() : resolvedValue;
                    }
                    break;
                case NUMBER:
                    // TODO
                    break;
                case FILE:
                    if (parent != null) {
                        final List<XnatFile> childStringList = matchChildFromParent(parent.getValue(), resolvedValue, "files", "name", resolvedMatcher, new TypeRef<List<XnatFile>>(){});
                        if (childStringList != null && !childStringList.isEmpty()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Selecting first matching result from list " + childStringList);
                            }
                            final XnatFile first = childStringList.get(0);
                            try {
                                resolvedValue = mapper.writeValueAsString(first);
                            } catch (JsonProcessingException e) {
                                log.error("Could not serialize file to json.", e);
                            }
                        }
                    } else {
                        throw new CommandInputResolutionException(String.format("Inputs of type %s must have a parent.", input.getType()), input);
                    }
                    break;
                case PROJECT:
                    // TODO
                    break;
                case SUBJECT:
                    // TODO
                    break;
                case SESSION:
                    if (parent != null) {
                        // We have a parent, so pull the value from it
                        // If we have any value set currently, assume it is an ID

                        final List<Session> childStringList = matchChildFromParent(parent.getValue(), resolvedValue, "sessions", "id", resolvedMatcher, new TypeRef<List<Session>>(){});
                        if (childStringList != null && !childStringList.isEmpty()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Selecting first matching result from list " + childStringList);
                            }
                            final Session first = childStringList.get(0);
                            try {
                                resolvedValue = mapper.writeValueAsString(first);
                            } catch (JsonProcessingException e) {
                                log.error("Could not serialize session to json.", e);
                            }
                        }
                    } else {
                        // With no parent, we were either given, A. a Session in json, B. a list of Sessions in json, or C. the session id
                        final Session matches;
                        try {
                            matches = resolveXnatModelValue(resolvedValue, resolvedMatcher, Session.class,
                                    new Function<String, Session>() {
                                        @Nullable
                                        @Override
                                        public Session apply(@Nullable String s) {
                                            final XnatImagesessiondata imagesessiondata = XnatImagesessiondata.getXnatImagesessiondatasById(s, userI, true);
                                            if (imagesessiondata != null) {
                                                return new Session(imagesessiondata, userI);
                                            }
                                            return null;
                                        }
                                    });
                        } catch (CommandInputResolutionException e) {
                            throw new CommandInputResolutionException(e.getMessage(), input);
                        }

                        if (matches != null) {
                            try {
                                resolvedValue = mapper.writeValueAsString(matches);
                            } catch (JsonProcessingException e) {
                                log.error("Could not serialize session: " + matches, e);
                            }
                        }
                    }
                    break;
                case SCAN:
                    if (parent != null) {
                        // We have a parent, so pull the value from it
                        final List<Scan> childStringList = matchChildFromParent(parent.getValue(), resolvedValue, "scans", "id", resolvedMatcher, new TypeRef<List<Scan>>(){});
                        if (childStringList != null && !childStringList.isEmpty()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Selecting first matching result from list " + childStringList);
                            }
                            final Scan first = childStringList.get(0);
                            try {
                                resolvedValue = mapper.writeValueAsString(first);
                            } catch (JsonProcessingException e) {
                                log.error("Could not serialize scan to json.", e);
                            }
                        }
                    } else {
                        // With no parent, we must have been given the Scan as json
                        final Scan matches;
                        try {
                            matches = resolveXnatModelValue(resolvedValue, resolvedMatcher, Scan.class, null);
                        } catch (CommandInputResolutionException e) {
                            throw new CommandInputResolutionException(e.getMessage(), input);
                        }

                        if (matches != null) {
                            try {
                                resolvedValue = mapper.writeValueAsString(matches);
                            } catch (JsonProcessingException e) {
                                log.error("Could not serialize scan: " + matches, e);
                            }
                        }
                    }
                    break;
                case ASSESSOR:
                    // TODO
                    break;
                case CONFIG:
                    final String[] configProps = resolvedValue != null ? resolvedValue.split("/") : null;
                    if (configProps == null || configProps.length != 2) {
                        throw new CommandInputResolutionException("Input value: " + resolvedValue + ". Config inputs must have a value that can be interpreted as a config_toolname/config_filename string.", input);
                    }

                    final Scope configScope;
                    final String entityId;
                    final CommandInput.Type parentType = parent == null ? CommandInput.Type.STRING : parent.getType();
                    switch (parentType) {
                        case PROJECT:
                            configScope = Scope.Project;
                            entityId = JsonPath.parse(parent.getValue()).read("$.id");
                            break;
                        case SUBJECT:
                        case SESSION:
                        case SCAN:
                        case ASSESSOR:
                            // TODO This probably will not work. Figure out a way to get the project ID from these, or simply throw an error.
                            configScope = Scope.Project;
                            final List<String> projectIds = JsonPath.parse(parent.getValue()).read("$..projectId");
                            entityId = (projectIds != null && !projectIds.isEmpty()) ? projectIds.get(0) : "";
                            if (StringUtils.isBlank(entityId)) {
                                throw new CommandInputResolutionException("Could not determine project when resolving config value.", input);
                            }
                            break;
                        default:
                            configScope = Scope.Site;
                            entityId = null;
                    }

                    final String configContents = configService.getConfigContents(configProps[0], configProps[1], configScope, entityId);
                    if (configContents == null) {
                        throw new CommandInputResolutionException("Could not read config " + resolvedValue, input);
                    }

                    resolvedValue = configContents;
                    break;
                case RESOURCE:
                    if (parent != null) {
                        // We have a parent, so pull the value from it
                        final List<Resource> childStringList = matchChildFromParent(parent.getValue(), resolvedValue, "resources", "id", resolvedMatcher, new TypeRef<List<Resource>>(){});
                        if (childStringList != null && !childStringList.isEmpty()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Selecting first matching result from list " + childStringList);
                            }
                            final Resource first = childStringList.get(0);
                            try {
                                resolvedValue = mapper.writeValueAsString(first);
                            } catch (JsonProcessingException e) {
                                log.error("Could not serialize resource to json.", e);
                            }
                        }
                    } else {
                        throw new CommandInputResolutionException(String.format("Inputs of type %s must have a parent.", input.getType()), input);
                    }
                    break;
                default:
                    if (parent != null && StringUtils.isNotBlank(input.getParentProperty())) {
                        final String propertyToGetFromParent = resolveJsonpathSubstring(input.getParentProperty());
                        final String parentProperty = JsonPath.parse(parent.getValue()).read("$." + propertyToGetFromParent);
                        if (parentProperty != null) {
                            resolvedValue = parentProperty;
                        }
                    }
            }


            // If resolved value is null, and input is required, that is an error
            if (resolvedValue == null && input.isRequired()) {
                final String message = String.format("No value could be resolved for required input \"%s\".", input.getName());
                throw new CommandInputResolutionException(message, input);
            }
            if (log.isInfoEnabled()) {
                final String message = String.format("Resolved input \"%s\": %s", input.getName(), resolvedValue);
                log.info(message);
            }
            input.setValue(resolvedValue);

            resolvedInputObjects.put(input.getName(), input);
            resolvedInputValuesByName.put(input.getName(), input.getValue());

            // Only substitute the input into the command line if a replacementKey is set
            final String replacementKey = input.getReplacementKey();
            if (StringUtils.isBlank(replacementKey)) {
                continue;
            }
            resolvedInputValuesByReplacementKey.put(replacementKey, resolvedValue);
            resolvedInputCommandLineValuesByReplacementKey.put(replacementKey, getValueForCommandLine(input, resolvedValue));
        }

        return resolvedInputValuesByName;
    }

    private String commandAsJson() throws CommandResolutionException {
        if (!command.equals(cachedCommand)) {
            cachedCommand = command;

            try {
                commandJson = mapper.writeValueAsString(cachedCommand);
            } catch (JsonProcessingException e) {
                throw new CommandResolutionException("Could not serialize command to json.", e);
            }
        }

        return commandJson;
    }

    private <T extends XnatModelObject> List<T> matchChildFromParent(final String parentValue, final String value, final String childKey, final String valueMatchProperty, final String matcherFromInput, final TypeRef<List<T>> typeRef) {
        final String matcherFromValue = StringUtils.isNotBlank(value) ?
                String.format("@.%s == '%s'", valueMatchProperty, value) :
                "";
        final boolean hasValueMatcher = StringUtils.isNotBlank(matcherFromValue);
        final boolean hasInputMatcher = StringUtils.isNotBlank(matcherFromInput);
        final String fullMatcher;
        if (hasValueMatcher && hasInputMatcher) {
            fullMatcher = matcherFromValue + " && " + matcherFromInput;
        } else if (hasValueMatcher) {
            fullMatcher = matcherFromValue;
        } else if (hasInputMatcher) {
            fullMatcher = matcherFromInput;
        } else {
            fullMatcher = "*";
        }
        return getChildFromParent(parentValue, childKey, fullMatcher, typeRef);
    }

    private <T extends XnatModelObject> List<T> getChildFromParent(final String parentJson, final String childKey, final String matcher, final TypeRef<List<T>> typeRef) {
        final String jsonPathSearch = String.format(
                "$.%s[%s]",
                childKey,
                StringUtils.isNotBlank(matcher) ? "?(" + matcher + ")" : "*"
        );
        if (log.isDebugEnabled()) {
            final String message = String.format("Attempting to pull value %s from parent json %s.", jsonPathSearch, parentJson);
            log.debug(message);
        }

        try {
            return JsonPath.parse(parentJson).read(jsonPathSearch, typeRef);
        } catch (InvalidJsonException | MappingException e) {
            final String message = String.format("Error attempting to pull value %s from parent json %s.", jsonPathSearch, parentJson);
            log.error(message, e);
        }
        return null;
    }

    private String getValueForCommandLine(final CommandInput input, final String resolvedInputValue) {
        if (StringUtils.isBlank(input.getCommandLineFlag())) {
            return resolvedInputValue;
        } else {
            return input.getCommandLineFlag() +
                    (input.getCommandLineSeparator() == null ? " " : input.getCommandLineSeparator()) +
                    resolvedInputValue;
        }
    }

    public <T extends XnatModelObject> T resolveXnatModelValue(final String value,
                                                               final String matcher,
                                                               final Class<T> model,
                                                               final Function<String, T> makeANewModelObject)
            throws CommandInputResolutionException {
        List<T> mayOrMayNotMatch = Lists.newArrayList();
        if (value.startsWith("{")) {
            try {
                if (log.isDebugEnabled()) {
                    final String message = String.format("Attempting to deserialize value %s as a %s.", value, model.getName());
                    log.debug(message);
                }
                final T modelObject = mapper.readValue(value, model);
                mayOrMayNotMatch.add(modelObject);
            } catch (IOException e) {
                log.info(String.format("Could not deserialize %s into a %s.", value, model.getName()), e);
            }
        } else if (value.matches("^\\[\\s*\\{")) {
            try {
                if (log.isDebugEnabled()) {
                    final String message = String.format("Attempting to deserialize value %s as a list of %s.", value, model.getName());
                    log.debug(message);
                }
                mayOrMayNotMatch = mapper.readValue(value, new TypeReference<List<T>>(){});
            } catch (IOException e) {
                log.error(String.format("Could not deserialize %s into a list of %s.", value, model.getName()), e);
            }
        } else if (makeANewModelObject != null) {
            final T newModelObject = makeANewModelObject.apply(value);
            if (newModelObject != null) {
                mayOrMayNotMatch.add(newModelObject);
            } else {
                if (log.isInfoEnabled()) {
                    log.info(String.format("Could not create a %s from %s.", model.getName(), value));
                }
            }
        }

        if (mayOrMayNotMatch == null || mayOrMayNotMatch.isEmpty()) {
            final String message = String.format("Could not instantiate %s from value %s.", model.getName(), value);
            throw new CommandInputResolutionException(message, null);
        }

        final List<T> doMatch;
        if (StringUtils.isNotBlank(matcher)) {
            final String jsonPathSearch = String.format(
                    "$[?(%s)]", matcher
            );
            doMatch = JsonPath.parse(mayOrMayNotMatch).read(jsonPathSearch, new TypeRef<List<T>>(){});

            if (doMatch == null || doMatch.isEmpty()) {
                throw new CommandInputResolutionException("Could not match any %s with matcher " + matcher, null);
            }
        } else {
            doMatch = mayOrMayNotMatch;
        }

        final T matches = doMatch.get(0);
        if (doMatch.size() > 1) {
            log.warn(String.format("Refusing to implicitly loop over %s objects. Selecting first (%s) from list (%s).", model.getName(), matches, value));
        }

        return matches;
    }

    private List<CommandOutput> resolveOutputs() throws CommandResolutionException {
        if (log.isDebugEnabled()) {
            log.debug("Resolving command outputs");
        }
        if (command.getOutputs() == null) {
            return null;
        }

        final List<CommandOutput> outputs = Lists.newArrayList();
        for (final CommandOutput output : command.getOutputs()) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Resolving output \"%s\"", output.getName()));
            }

            final CommandOutputFiles files = output.getFiles();
            // TODO This should be noticed and fixed during command validation
            if (files == null) {
                throw new CommandResolutionException("Command output \"%s\" has no files.");
            }

            final String resolvedPath = resolveTemplate(files.getPath());
            files.setPath(resolvedPath);

            // TODO Anything else needed to resolve an output?

            if (log.isDebugEnabled()) {
                log.debug(String.format("Adding resolved output \"%s\" to resolved command.", output.getName()));
            }

            outputs.add(output);
        }

        return outputs;
    }

    private String resolveCommandLine() throws CommandResolutionException {
        if (log.isDebugEnabled()) {
            log.debug("Resolving command-line string");
        }
        return resolveTemplate(command.getRun().getCommandLine(), resolvedInputCommandLineValuesByReplacementKey);
    }

    private Map<String, String> resolveEnvironmentVariables()
            throws CommandResolutionException {
        if (log.isDebugEnabled()) {
            log.debug("Resolving environment variables");
        }
        final Map<String, String> envTemplates = command.getRun().getEnvironmentVariables();
        if (envTemplates == null) {
            return null;
        }

        final Map<String, String> resolvedMap = Maps.newHashMap();
        for (final Map.Entry<String, String> templateEntry : envTemplates.entrySet()) {
            final String resolvedKey = resolveTemplate(templateEntry.getKey());
            final String resolvedValue = resolveTemplate(templateEntry.getValue());
            resolvedMap.put(resolvedKey, resolvedValue);
            if (!templateEntry.getKey().equals(resolvedKey) || !templateEntry.getValue().equals(resolvedValue)) {
                if (log.isDebugEnabled()) {
                    final String message = String.format("Map %s: %s -> %s: %s",
                            templateEntry.getKey(), templateEntry.getValue(),
                            resolvedKey, resolvedValue);
                    log.debug(message);
                }
            }
        }
        return resolvedMap;
    }

    private List<CommandMount> resolveCommandMounts() throws CommandMountResolutionException {
        if (log.isDebugEnabled()) {
            log.debug("Resolving mounts");
        }
        if (command.getRun() == null || command.getRun().getMounts() == null) {
            if (log.isDebugEnabled()) {
                log.debug("No mounts");
            }
            return Lists.newArrayList();
        }

        final List<CommandMount> commandMounts = Lists.newArrayList();
        for (final CommandMount mount : command.getRun().getMounts()) {
            if (mount.isInput()) {
                mount.setHostPath(resolveCommandMountHostPath(mount));
            }
//                mount.setRemotePath(resolveTemplate(mount.getRemotePath(), resolvedInputs));
            commandMounts.add(mount);
        }

        if (log.isDebugEnabled()) {
            log.debug("Done resolving mounts.");
        }
        return commandMounts;
    }

    private String resolveCommandMountHostPath(final CommandMount mount) throws CommandMountResolutionException {
        if (log.isDebugEnabled()) {
            log.debug(String.format("Resolving hostPath for mount \"%s\".", mount.getName()));
        }

        final String hostPath;
        if (StringUtils.isNotBlank(mount.getFileInput())) {

            final CommandInput sourceInput = resolvedInputObjects.get(mount.getFileInput());
            if (sourceInput == null || StringUtils.isBlank(sourceInput.getValue())) {
                final String message = String.format("Cannot resolve mount \"%s\". Source input \"%s\" has no resolved value.", mount.getName(), mount.getFileInput());
                throw new CommandMountResolutionException(message, mount);
            }
            final String sourceInputValue = sourceInput.getValue();
            if (log.isDebugEnabled()) {
                log.debug(String.format("Source input has type \"%s\".", sourceInput.getType()));
            }
            switch (sourceInput.getType()) {
                case RESOURCE:
                    try {
                        final Resource resource = mapper.readValue(sourceInputValue, Resource.class);
                        hostPath = resource.getDirectory();
                    } catch (IOException e) {
                        throw new CommandMountResolutionException("Source input is not a Resource. " + sourceInput, mount, e);
                    }
                    break;
                case FILE:
                    hostPath = sourceInput.getValue();
                    break;
                case PROJECT:
                case SUBJECT:
                case SESSION:
                case SCAN:
                case ASSESSOR:
                    if (log.isDebugEnabled()) {
                        log.debug("Looking for child resources on source input.");
                    }
                    final List<Resource> resources = JsonPath.parse(sourceInputValue).read("$.resources[*]", new TypeRef<List<Resource>>(){});
                    if (resources == null || resources.isEmpty()) {
                        throw new CommandMountResolutionException(String.format("Could not find any resources for source input \"%s\".", sourceInput), mount);
                    }

                    if (StringUtils.isBlank(mount.getResource()) || resources.size() == 1) {
                        hostPath = resources.get(0).getDirectory();
                    } else {
                        String directory = null;
                        for (final Resource resource : resources) {
                            if (resource.getLabel().equals(mount.getResource())) {
                                directory = resource.getDirectory();
                                break;
                            }
                        }
                        if (StringUtils.isNotBlank(directory)) {
                            hostPath = directory;
                        } else {
                            throw new CommandMountResolutionException(String.format("Source input \"%s\" has no resource with label \"%s\".", sourceInput.getName(), mount.getResource()), mount);
                        }
                    }

                    break;
                default:
                    throw new CommandMountResolutionException("I don't know how to resolve a mount from an input of type " + sourceInput.getType(), mount);
            }
        } else {
            throw new CommandMountResolutionException("I don't know how to resolve a mount without a source input.", mount);
        }

        if (StringUtils.isBlank(hostPath)) {
            throw new CommandMountResolutionException("Could not resolve command mount host path.", mount);
        }

        if (log.isDebugEnabled()) {
            log.debug("Resolved host path: " + hostPath);
        }
        return hostPath;
    }

    private String resolveTemplate(final String template)
            throws CommandResolutionException {
        return resolveTemplate(template, resolvedInputValuesByReplacementKey);
    }

    private String resolveTemplate(final String template, Map<String, String> valuesMap)
            throws CommandResolutionException {
        if (log.isDebugEnabled()) {
            log.debug("Resolving: " + template);
        }
        String toResolve = resolveJsonpathSubstring(template);

        for (final String replacementKey : valuesMap.keySet()) {
            final String replacementValue = valuesMap.get(replacementKey);
            final String copyForLogging = log.isDebugEnabled() ? toResolve : null;
            toResolve = toResolve.replaceAll(replacementKey, replacementValue);
            if (log.isDebugEnabled() && copyForLogging != null && !toResolve.equals(copyForLogging)) {
                log.debug(String.format("%s -> %s", replacementKey, replacementValue));
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Resolved: " + toResolve);
        }
        return toResolve;
    }

    private String resolveJsonpathSubstring(final String stringThatMayContainJsonpathSubstring) throws CommandResolutionException {
        if (log.isDebugEnabled()) log.debug("Checking for jsonpath substring in " + stringThatMayContainJsonpathSubstring);
        if (StringUtils.isNotBlank(stringThatMayContainJsonpathSubstring)) {

            final Matcher jsonpathSubstringMatcher = jsonpathSubstringPattern.matcher(stringThatMayContainJsonpathSubstring);

            if (jsonpathSubstringMatcher.find()) {

                final String jsonpathSearchWithMarkers = jsonpathSubstringMatcher.group(0);
                final String jsonpathSearchWithoutMarkers = jsonpathSubstringMatcher.group(1);

                if (log.isDebugEnabled()) log.debug("Found possible jsonpath substring " + jsonpathSearchWithMarkers);

                if (StringUtils.isNotBlank(jsonpathSearchWithoutMarkers)) {

                    if(log.isInfoEnabled()) log.info("Performing jsonpath search through command with search string " + jsonpathSearchWithoutMarkers);
                    if(log.isDebugEnabled()) log.debug("Command json: " + commandAsJson());

                    final Configuration c = Configuration.defaultConfiguration().addOptions(Option.ALWAYS_RETURN_LIST);
                    final List<String> searchResult = JsonPath.using(c).parse(commandAsJson()).read(jsonpathSearchWithoutMarkers);
                    if (searchResult != null && !searchResult.isEmpty() && searchResult.get(0) != null) {
                        if (log.isInfoEnabled()) log.info("Result: " + searchResult);
                        if (searchResult.size() == 1) {
                            final String result = searchResult.get(0);
                            final String replacement = stringThatMayContainJsonpathSubstring.replace(jsonpathSearchWithMarkers, result);
                            if (log.isDebugEnabled()) {
                                log.debug(String.format("Replacing %s with %s in %s.", jsonpathSearchWithMarkers, result, stringThatMayContainJsonpathSubstring));
                                log.debug("Result: " + replacement);
                            }
                            return replacement;
                        } else {
                            final String message =
                                    String.format(
                                            "JSONPath search %s resulted in multiple results: %s. Cannot determine value to replace into string %s.",
                                            jsonpathSearchWithoutMarkers,
                                            searchResult.toString(),
                                            stringThatMayContainJsonpathSubstring);
                            throw new CommandResolutionException(message);
                        }
                    } else {
                        if (log.isDebugEnabled()) log.debug("No result");
                    }
                }
            }
        }
        if (log.isDebugEnabled()) log.debug("Returning initial string without any replacement.");
        return stringThatMayContainJsonpathSubstring;
    }
}