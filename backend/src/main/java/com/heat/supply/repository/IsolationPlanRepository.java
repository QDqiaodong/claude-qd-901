package com.heat.supply.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heat.supply.domain.IsolationPlan;

public interface IsolationPlanRepository extends JpaRepository<IsolationPlan, Long> {

    Optional<IsolationPlan> findByPlanNo(String planNo);

    List<IsolationPlan> findAllByOrderByCreatedTimeDesc();

    List<IsolationPlan> findByParentPlanId(Long parentPlanId);
}
