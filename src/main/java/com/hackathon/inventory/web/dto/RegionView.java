package com.hackathon.inventory.web.dto;

public record RegionView(Long id, String code, String name, String hqCity, boolean active, long shopCount) {}
