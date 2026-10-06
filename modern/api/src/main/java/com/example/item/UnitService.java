package com.example.item;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UnitService {

    private static final Logger log = LoggerFactory.getLogger(UnitService.class);

    private final UnitRepository unitRepository;
    private final ItemRepository itemRepository;

    public UnitService(UnitRepository unitRepository, ItemRepository itemRepository) {
        this.unitRepository = unitRepository;
        this.itemRepository = itemRepository;
    }

    /** 단원 목록 — 학년, 코드 순. */
    public List<UnitResponse> listUnits() {
        List<UnitResponse> units = unitRepository.findAllByOrderByGradeAscCodeAsc().stream()
            .map(UnitResponse::from)
            .toList();
        log.debug("listed {} units", units.size());
        return units;
    }

    /**
     * 단원별 문항 수 통계 — 단원마다 공개({@link ItemStatus#ACTIVE}) 문항 수를 센다.
     * 순서는 {@link #listUnits()} 와 같다(학년, 코드 순). 문항이 없는 단원은 0 으로 포함한다.
     */
    public List<UnitItemCountResponse> countActiveItemsByUnit() {
        List<UnitItemCountResponse> counts = unitRepository.findAllByOrderByGradeAscCodeAsc().stream()
            .map(unit -> UnitItemCountResponse.of(
                unit, itemRepository.countByUnitIdAndStatus(unit.getId(), ItemStatus.ACTIVE)))
            .toList();
        log.debug("counted active items for {} units", counts.size());
        return counts;
    }
}
