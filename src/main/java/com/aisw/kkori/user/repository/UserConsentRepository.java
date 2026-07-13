package com.aisw.kkori.user.repository;

import com.aisw.kkori.user.domain.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {
}
