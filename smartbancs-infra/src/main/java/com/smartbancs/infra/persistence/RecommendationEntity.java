package com.smartbancs.infra.persistence;

import com.smartbancs.domain.entity.Recommendation;
import com.smartbancs.domain.enums.RecommendationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recommendations")
public class RecommendationEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecommendationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecommendationEntity() {
    }

    public static RecommendationEntity fromDomain(Recommendation recommendation) {
        RecommendationEntity entity = new RecommendationEntity();
        entity.id = recommendation.getId();
        entity.customerId = recommendation.getCustomerId();
        entity.type = recommendation.getType();
        entity.payload = recommendation.getPayload();
        entity.status = recommendation.getStatus();
        entity.createdAt = recommendation.getCreatedAt();
        entity.updatedAt = recommendation.getUpdatedAt();
        return entity;
    }

    public Recommendation toDomain() {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(id);
        recommendation.setCustomerId(customerId);
        recommendation.setType(type);
        recommendation.setPayload(payload);
        recommendation.setStatus(status);
        recommendation.setCreatedAt(createdAt);
        recommendation.setUpdatedAt(updatedAt);
        return recommendation;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public RecommendationStatus getStatus() {
        return status;
    }

    public void setStatus(RecommendationStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}