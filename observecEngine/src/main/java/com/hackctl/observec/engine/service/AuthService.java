package com.hackctl.observec.engine.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hackctl.observec.engine.component.UserStore;
import com.hackctl.observec.engine.model.User;

@Service
public class AuthService {

    private final UserStore userStore;

    public AuthService(UserStore userStore) {
        this.userStore = userStore;
    }

    public Optional<User> authenticate(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        Optional<User> opt = userStore.findByUsername(username.trim());
        if (opt.isEmpty()) return Optional.empty();
        User u = opt.get();
        if (userStore.verifyPassword(u, password)) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    public User getUserById(Long id) {
        return userStore.findById(id).orElse(null);
    }

    public User getUserByUsername(String username) {
        return userStore.findByUsername(username).orElse(null);
    }
}
