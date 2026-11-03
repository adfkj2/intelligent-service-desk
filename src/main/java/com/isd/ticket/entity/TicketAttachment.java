package com.isd.ticket.entity;

import java.time.LocalDateTime;

public class TicketAttachment {
    private Long id;
    private Long ticketId;
    private Long uploaderId;
    private String uploaderName;
    private String fileName;
    private String contentType;
    private Long size;
    private String visibility;
    private byte[] data;
    private LocalDateTime createdAt;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long value) { this.ticketId = value; }
    public Long getUploaderId() { return uploaderId; }
    public void setUploaderId(Long value) { this.uploaderId = value; }
    public String getUploaderName() { return uploaderName; }
    public void setUploaderName(String value) { this.uploaderName = value; }
    public String getFileName() { return fileName; }
    public void setFileName(String value) { this.fileName = value; }
    public String getContentType() { return contentType; }
    public void setContentType(String value) { this.contentType = value; }
    public Long getSize() { return size; }
    public void setSize(Long value) { this.size = value; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String value) { this.visibility = value; }
    public byte[] getData() { return data; }
    public void setData(byte[] value) { this.data = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
}
