package com.example.websocketsql;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "users")
public class User {
    @Id
    private String username;
    private String passwordHash;

    public User() {}
    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
