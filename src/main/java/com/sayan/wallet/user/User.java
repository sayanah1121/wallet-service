package com.sayan.wallet.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor 

public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

     @Column(name = "full_name", nullable = false)
    private String fullName;


    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;
    @Column (nullable = false)
    private boolean enabled = true;

    @Column( name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
     void onCreate() {
        createdAt = Instant.now();
    }
    
}
