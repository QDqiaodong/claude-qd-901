package com.heat.supply.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.heat.supply.domain.IsolationStep;

public interface IsolationStepRepository extends JpaRepository<IsolationStep, Long> {

    List<IsolationStep> findByPlanIdOrderByStepNoAsc(Long planId);

    void deleteByPlanId(Long planId);
}
