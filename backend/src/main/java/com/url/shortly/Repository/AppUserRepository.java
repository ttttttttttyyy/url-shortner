package com.url.shortly.Repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.url.shortly.Entities.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Emails are normalized before every lookup and insert. */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
