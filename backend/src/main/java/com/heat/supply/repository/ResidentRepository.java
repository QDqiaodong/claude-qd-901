package com.heat.supply.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.heat.supply.domain.Resident;

public interface ResidentRepository extends JpaRepository<Resident, Long> {

    List<Resident> findByStationIdOrderByBuildingNoAscUnitNoAsc(Long stationId);

    List<Resident> findByCommunityOrderByBuildingNoAscUnitNoAscRoomNoAsc(String community);

    List<Resident> findByBuildingNoAndUnitNoOrderByRoomNoAsc(String buildingNo, String unitNo);

    @Query("select distinct r.community from Resident r")
    List<String> findAllCommunities();

    long countBySupplyStatus(String supplyStatus);
}
