package com.hackathon.appointments.service;

import com.hackathon.appointments.config.AppProperties;
import com.hackathon.appointments.exception.BadRequestException;
import com.hackathon.appointments.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a safe {@link Pageable} from raw query params: whitelisted sort fields (no sorting on arbitrary
 * columns / injection), bounded page size, and a deterministic tie-breaker on id so paging is stable.
 */
@Component
public class PageRequests {

    private final AppProperties props;

    public PageRequests(AppProperties props) {
        this.props = props;
    }

    /**
     * @param sortParams values like {@code "startTime,asc"} or {@code "providerName,desc"}
     * @param allowed    public sort name -> JPA property path
     */
    public Pageable of(int page, int size, List<String> sortParams, Map<String, String> allowed, String defaultSort) {
        if (page < 0) {
            throw new BadRequestException(ErrorCode.INVALID_REQUEST, "page must be >= 0");
        }
        if (size < 1 || size > props.maxPageSize()) {
            throw new BadRequestException(ErrorCode.INVALID_REQUEST, "size must be between 1 and " + props.maxPageSize());
        }
        // Spring may hand us "startTime,desc" as one value or as two ("startTime", "desc"); normalise to tokens.
        List<String> tokens = new ArrayList<>();
        for (String p : (sortParams == null || sortParams.isEmpty()) ? List.of(defaultSort) : sortParams) {
            for (String t : p.split(",")) {
                if (!t.isBlank()) {
                    tokens.add(t.trim());
                }
            }
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String token : tokens) {
            if (token.equalsIgnoreCase("asc") || token.equalsIgnoreCase("desc")) {
                if (orders.isEmpty()) {
                    throw new BadRequestException(ErrorCode.INVALID_REQUEST, "Sort direction must follow a field name");
                }
                Sort.Order last = orders.remove(orders.size() - 1);
                orders.add(last.with(Sort.Direction.fromString(token)));
                continue;
            }
            String path = allowed.get(token);
            if (path == null) {
                throw new BadRequestException(ErrorCode.INVALID_REQUEST,
                        "Cannot sort by '%s'. Allowed: %s".formatted(token, allowed.keySet()));
            }
            orders.add(Sort.Order.asc(path));
        }
        if (orders.stream().noneMatch(o -> o.getProperty().equals("id"))) {
            orders.add(Sort.Order.asc("id"));
        }
        return PageRequest.of(page, size, Sort.by(orders));
    }
}
