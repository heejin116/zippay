package com.zippay.auth.repository;

import com.zippay.auth.domain.Email;
import com.zippay.auth.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(Email email);
    boolean existsByEmail(Email email);
}
