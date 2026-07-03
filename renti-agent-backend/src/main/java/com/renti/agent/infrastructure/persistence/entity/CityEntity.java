package com.renti.agent.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 城市库：驱动首页/城市选择页，enabled 城市可进入工作台。
 */
@Entity
@Table(name = "cities", indexes = {
        @Index(name = "idx_cities_slug", columnList = "slug", unique = true),
        @Index(name = "idx_cities_name", columnList = "name"),
})
@Getter
@Setter
@NoArgsConstructor
public class CityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 64)
    private String slug;

    @Column(name = "name_en", length = 64)
    private String nameEn;

    @Column(length = 64)
    private String province;

    @Column(length = 16)
    private String adcode;

    @Column(nullable = false)
    private boolean enabled = false;

    /** available | coming_soon 等展示状态 */
    @Column(nullable = false, length = 32)
    private String status = "coming_soon";

    @Column(name = "default_longitude")
    private Double defaultLongitude;

    @Column(name = "default_latitude")
    private Double defaultLatitude;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
