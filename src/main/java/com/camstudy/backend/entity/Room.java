package com.camstudy.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rooms")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Room {

    @Id
    private String id;

    private String name;

    private Integer capacity;

    private Boolean isPrivate;

    private String passwordHash;

    private String ownerId;

    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        if (capacity == null) capacity = 6;
        this.isPrivate = (passwordHash != null && !passwordHash.isBlank());
    }
}
