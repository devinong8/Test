package com.hackathon.inventory.web.dto;

import java.util.List;

public record MovementResult(long onHandAfter, List<TransactionView> transactions, List<String> warnings) {}
