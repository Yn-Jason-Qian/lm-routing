package com.lm.routing.model.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoutePlanRepository extends JpaRepository<RoutePlan, String> {
}
