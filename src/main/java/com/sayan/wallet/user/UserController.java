package com.sayan.wallet.user;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserRepository users;

    public record MeResponse(UUID id, String email, String fullName, Role role) {}

    @GetMapping("/me")
    public MeResponse me(Authentication auth) {
        User u = users.findById(UUID.fromString(auth.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new MeResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole());
    }
}