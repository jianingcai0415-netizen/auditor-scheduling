package org.acme.employeescheduling.dto;

import java.time.LocalDateTime;

/** Payload used by dispatch/external systems to publish a live auditor state. */
public class EmployeeStatusUpdate {
    private String operationalStatus;
    private LocalDateTime busyUntil;
    private Integer currentLocationId;
    private Integer bufferBeforeMinutes;
    private Integer bufferAfterMinutes;

    public String getOperationalStatus() { return operationalStatus; }
    public void setOperationalStatus(String operationalStatus) { this.operationalStatus = operationalStatus; }
    public LocalDateTime getBusyUntil() { return busyUntil; }
    public void setBusyUntil(LocalDateTime busyUntil) { this.busyUntil = busyUntil; }
    public Integer getCurrentLocationId() { return currentLocationId; }
    public void setCurrentLocationId(Integer currentLocationId) { this.currentLocationId = currentLocationId; }
    public Integer getBufferBeforeMinutes() { return bufferBeforeMinutes; }
    public void setBufferBeforeMinutes(Integer bufferBeforeMinutes) { this.bufferBeforeMinutes = bufferBeforeMinutes; }
    public Integer getBufferAfterMinutes() { return bufferAfterMinutes; }
    public void setBufferAfterMinutes(Integer bufferAfterMinutes) { this.bufferAfterMinutes = bufferAfterMinutes; }
}
