package org.nrg.containers.events;

import com.google.common.base.MoreObjects;

import java.util.Date;
import java.util.Map;
import java.util.Objects;

public class DockerContainerEvent implements DockerEvent {
    private String status;
    private String containerId;
    private Date time;
    private Long timeNano;
    private Map<String, Object> attributes;

    public DockerContainerEvent(final String status,
                                final String containerId,
                                final Date time,
                                final Long timeNano,
                                final Map<String, Object> attributes) {
        this.status = status;
        this.containerId = containerId;
        this.time = time;
        this.timeNano = timeNano;
        this.attributes = attributes;
    }

    public String getStatus() {
        return status;
    }

    public String getContainerId() {
        return containerId;
    }

    public Date getTime() {
        return time == null ? null : new Date(time.getTime());
    }

    public Long getTimeNano() {
        return timeNano;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final DockerContainerEvent that = (DockerContainerEvent) o;
        return Objects.equals(this.status, that.status) &&
                Objects.equals(this.containerId, that.containerId) &&
                Objects.equals(this.time, that.time) &&
                Objects.equals(this.timeNano, that.timeNano) &&
                Objects.equals(this.attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, containerId, time, timeNano, attributes);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("status", status)
                .add("containerId", containerId)
                .add("time", time)
                .add("timeNano", timeNano)
                .add("attributes", attributes)
                .toString();
    }
}
