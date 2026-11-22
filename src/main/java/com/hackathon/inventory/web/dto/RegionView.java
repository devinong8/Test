package com.hackathon.inventory.web.dto;

public record RegionView(Long id, String code, String name, boolean active, long shopCount) {}
