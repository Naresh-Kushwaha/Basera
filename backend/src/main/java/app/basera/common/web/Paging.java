package app.basera.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Pagination guard: page >= 0, size between 1 and 50 (default 20). */
public final class Paging {

    public static final int MAX_SIZE = 50;

    private Paging() {}

    public static Pageable of(int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_SIZE);
        return PageRequest.of(p, s);
    }
}
