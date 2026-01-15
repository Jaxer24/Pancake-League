package com.example.websocketsql;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    @Autowired
    private UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody User user) {
        try {
            if (userRepository.existsById(user.getUsername())) {
                return ResponseEntity.badRequest().body("Username already exists");
            }
            String hash = BCrypt.hashpw(user.getPasswordHash(), BCrypt.gensalt());
            user.setPasswordHash(hash);
            userRepository.save(user);
            return ResponseEntity.ok("Registered");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Incorrect username or password");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody User user) {
        User found = userRepository.findByUsername(user.getUsername());
        if (found == null || !BCrypt.checkpw(user.getPasswordHash(), found.getPasswordHash())) {
            return ResponseEntity.status(401).body("Invalid credentials");
        }
        return ResponseEntity.ok("Login successful");
    }
}
