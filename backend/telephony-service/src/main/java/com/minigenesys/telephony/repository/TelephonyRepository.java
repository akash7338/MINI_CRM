package com.minigenesys.telephony.repository;

import com.minigenesys.telephony.model.TelephonyCallSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TelephonyRepository extends JpaRepository<TelephonyCallSession, String> {
    Optional<TelephonyCallSession> findByTwilioCallSid(String twilioCallSid);
}
