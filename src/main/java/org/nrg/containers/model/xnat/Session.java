package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatImageassessordataI;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.model.XnatImagesessiondataI;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xft.security.UserI;

import java.util.List;
import java.util.Objects;


public class Session extends XnatModelObject {
    @JsonIgnore private XnatImagesessiondataI xnatImagesessiondataI;
    @JsonProperty(value = "parent-id") private String parentId;
    private List<Scan> scans;
    private List<Assessor> assessors;
    private List<Resource> resources;

    public Session() {}

    public Session(final XnatImagesessiondataI xnatImagesessiondataI, final UserI userI) {
        this(xnatImagesessiondataI, XnatProjectdata.getXnatProjectdatasById(xnatImagesessiondataI.getProject(), userI, false).getRootArchivePath());
    }
    public Session(final XnatImagesessiondataI xnatImagesessiondataI, final String rootArchivePath) {
        this.xnatImagesessiondataI = xnatImagesessiondataI;
        this.id = xnatImagesessiondataI.getId();
        this.label = xnatImagesessiondataI.getLabel();
        this.xsiType = xnatImagesessiondataI.getXSIType();

        this.parentId = xnatImagesessiondataI.getSubjectId();

        this.scans = Lists.newArrayList();
        for (final XnatImagescandataI xnatImagescandataI : xnatImagesessiondataI.getScans_scan()) {
            this.scans.add(new Scan(xnatImagescandataI, this.id, rootArchivePath));
        }

        this.resources = Lists.newArrayList();
        for (final XnatAbstractresourceI xnatAbstractresourceI : xnatImagesessiondataI.getResources_resource()) {
            if (xnatAbstractresourceI instanceof XnatResourcecatalog) {
                resources.add(new Resource((XnatResourcecatalog) xnatAbstractresourceI, this.id, rootArchivePath));
            }
        }

        this.assessors = Lists.newArrayList();
        for (final XnatImageassessordataI xnatImageassessordataI : xnatImagesessiondataI.getAssessors_assessor()) {
            assessors.add(new Assessor(xnatImageassessordataI, this.id, rootArchivePath));
        }
    }

    public XnatImagesessiondataI getXnatImagesessiondataI() {
        return xnatImagesessiondataI;
    }

    public void setXnatImagesessiondataI(final XnatImagesessiondataI xnatImagesessiondataI) {
        this.xnatImagesessiondataI = xnatImagesessiondataI;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(final String parentId) {
        this.parentId = parentId;
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(final List<Resource> resources) {
        this.resources = resources;
    }

    public List<Assessor> getAssessors() {
        return assessors;
    }

    public void setAssessors(final List<Assessor> assessors) {
        this.assessors = assessors;
    }

    public List<Scan> getScans() {
        return scans;
    }

    public void setScans(final List<Scan> scans) {
        this.scans = scans;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        final Session that = (Session) o;
        return Objects.equals(this.xnatImagesessiondataI, that.xnatImagesessiondataI) &&
                Objects.equals(this.parentId, that.parentId) &&
                Objects.equals(this.scans, that.scans) &&
                Objects.equals(this.assessors, that.assessors) &&
                Objects.equals(this.resources, that.resources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), xnatImagesessiondataI, parentId, scans, assessors, resources);
    }

    @Override
    public String toString() {
        return addParentPropertiesToString(MoreObjects.toStringHelper(this))
                .add("parentId", parentId)
                .add("scans", scans)
                .add("assessors", assessors)
                .add("resources", resources)
                .toString();
    }
}