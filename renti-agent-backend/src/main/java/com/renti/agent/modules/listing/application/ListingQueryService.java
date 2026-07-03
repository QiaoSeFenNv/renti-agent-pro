package com.renti.agent.modules.listing.application;

import java.util.List;
import java.util.Optional;

import com.renti.agent.infrastructure.persistence.entity.ListingEntity;
import com.renti.agent.infrastructure.persistence.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 已发布房源查询：供 search/rag/graph 等其他模块复用的公开入口。
 */
@Service
@RequiredArgsConstructor
public class ListingQueryService {

    private final ListingRepository listingRepository;

    /** 指定城市的全部在架房源 */
    @Transactional(readOnly = true)
    public List<ListingEntity> findActiveByCity(String city) {
        return listingRepository.findByCityAndStatus(city, "active");
    }

    /** 按业务 ID 查在架房源 */
    @Transactional(readOnly = true)
    public Optional<ListingEntity> findActive(String listingId) {
        return listingRepository.findById(listingId)
                .filter(listing -> "active".equals(listing.getStatus()));
    }
}
