package com.minigenesys.callservice.repository;

import com.minigenesys.callservice.model.Call;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CallRepository extends JpaRepository<Call, String> {
}
