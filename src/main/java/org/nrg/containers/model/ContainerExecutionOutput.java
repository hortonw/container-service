package org.nrg.containers.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.nrg.containers.model.xnat.XnatModelObject;

import javax.persistence.Embeddable;
import javax.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ContainerExecutionOutput implements Serializable {
    private String name;
    private OutputType type;
    private String label;
    private Boolean required;
    @JsonProperty("parent") private String parentInputName;
    private String mount;
    private String path;
    private String created;

    public ContainerExecutionOutput() {}

    public ContainerExecutionOutput(final CommandOutput commandOutput) {
        this.name = commandOutput.getName();
        this.type = commandOutput.getType();
        this.label = commandOutput.getLabel();
        this.required = commandOutput.getRequired();
        this.parentInputName = commandOutput.getParent();
        this.mount = commandOutput.getFiles() != null ?
                commandOutput.getFiles().getMount() : "";
        this.path = commandOutput.getFiles() != null ?
                commandOutput.getFiles().getPath() : "";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public OutputType getType() {
        return type;
    }

    public void setType(final OutputType type) {
        this.type = type;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String label) {
        this.label = label;
    }

    public Boolean getRequired() {
        return required;
    }

    @Transient
    public boolean isRequired() {
        return required != null && required;
    }

    public void setRequired(final Boolean required) {
        this.required = required;
    }

    public String getParentInputName() {
        return parentInputName;
    }

    public void setParentInputName(final String parent) {
        this.parentInputName = parent;
    }

    public String getMount() {
        return mount;
    }

    public void setMount(final String mount) {
        this.mount = mount;
    }

    public String getPath() {
        return path;
    }

    public void setPath(final String path) {
        this.path = path;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(final String created) {
        this.created = created;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ContainerExecutionOutput that = (ContainerExecutionOutput) o;
        return Objects.equals(this.name, that.name) &&
                Objects.equals(this.type, that.type) &&
                Objects.equals(this.label, that.label) &&
                Objects.equals(this.required, that.required) &&
                Objects.equals(this.parentInputName, that.parentInputName) &&
                Objects.equals(this.mount, that.mount) &&
                Objects.equals(this.path, that.path) &&
                Objects.equals(this.created, that.created);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, label, required, parentInputName, mount, path, created);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("type", type)
                .add("label", label)
                .add("required", required)
                .add("parent", parentInputName)
                .add("mount", mount)
                .add("path", path)
                .add("created", created)
                .toString();
    }
}