package com.minigenesys.telephony.repository;

import com.minigenesys.telephony.model.PhoneNumberMapping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhoneNumberMappingRepository extends JpaRepository<PhoneNumberMapping, String> {
}
