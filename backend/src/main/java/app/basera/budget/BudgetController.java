package app.basera.budget;

import static app.basera.common.db.Db.p;

import app.basera.common.db.Ctx;
import app.basera.common.db.Db;
import app.basera.common.db.Modules;
import app.basera.common.error.ApiException;
import app.basera.common.validation.TextSanitizer;
import app.basera.common.web.PageResponse;
import app.basera.common.web.Paging;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Simple monthly expense tracker. Private to the member (every query filters on the caller's membership). */
@RestController
@RequestMapping("/api/v1/budget")
public class BudgetController {

    private static final Set<String> CATEGORIES = Set.of("RENT", "FOOD", "TRANSPORT", "UTILITIES", "HEALTH", "EDUCATION", "ENTERTAINMENT", "OTHER");

    public record ExpenseRequest(BigDecimal amount, String category, LocalDate spentOn, String note) {}

    private final Db db;
    private final Modules modules;

    public BudgetController(Db db, Modules modules) {
        this.db = db;
        this.modules = modules;
    }

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> add(@RequestBody ExpenseRequest r) {
        modules.require("BUDGET");
        validate(r);
        return Map.of("id", db.id("""
                INSERT INTO expense (tenant_id, membership_id, amount, category, spent_on, note)
                VALUES (app_current_tenant(), :me, :amount, :cat, :on, :note) RETURNING id""", params(r)));
    }

    @PutMapping("/expenses/{id}")
    @Transactional
    public ResponseEntity<Void> update(@PathVariable UUID id, @RequestBody ExpenseRequest r) {
        validate(r);
        Map<String, Object> params = params(r);
        params.put("id", id);
        db.execOrNotFound("UPDATE expense SET amount = :amount, category = :cat, spent_on = :on, note = :note WHERE id = :id AND membership_id = :me",
                params, "EXPENSE_NOT_FOUND", "Expense not found");
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/expenses/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        db.execOrNotFound("DELETE FROM expense WHERE id = :id AND membership_id = :me", p("id", id, "me", Ctx.membershipId()),
                "EXPENSE_NOT_FOUND", "Expense not found");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/expenses")
    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> list(@RequestParam(required = false) String month,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "30") int size) {
        modules.require("BUDGET");
        YearMonth ym = parseMonth(month);
        Pageable pg = Paging.of(page, size);
        Map<String, Object> params = p("me", Ctx.membershipId(), "from", ym.atDay(1), "to", ym.plusMonths(1).atDay(1));
        long total = db.count("SELECT count(*) FROM expense WHERE membership_id = :me AND spent_on >= :from AND spent_on < :to", params);
        params.put("limit", pg.getPageSize());
        params.put("offset", (int) pg.getOffset());
        List<Map<String, Object>> items = db.rows("""
                SELECT id, amount, category, spent_on AS "spentOn", note FROM expense
                WHERE membership_id = :me AND spent_on >= :from AND spent_on < :to
                ORDER BY spent_on DESC, created_at DESC LIMIT :limit OFFSET :offset""", params);
        return PageResponse.of(items, pg.getPageNumber(), pg.getPageSize(), total);
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public Map<String, Object> summary(@RequestParam(required = false) String month) {
        modules.require("BUDGET");
        YearMonth ym = parseMonth(month);
        Map<String, Object> params = p("me", Ctx.membershipId(), "from", ym.atDay(1), "to", ym.plusMonths(1).atDay(1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym.toString());
        out.put("total", db.rows("SELECT COALESCE(sum(amount), 0) AS total FROM expense WHERE membership_id = :me AND spent_on >= :from AND spent_on < :to", params)
                .get(0).get("total"));
        out.put("byCategory", db.rows("""
                SELECT category, sum(amount) AS total FROM expense WHERE membership_id = :me AND spent_on >= :from AND spent_on < :to
                GROUP BY category ORDER BY total DESC""", params));
        out.put("daily", db.rows("""
                SELECT spent_on AS date, sum(amount) AS total FROM expense WHERE membership_id = :me AND spent_on >= :from AND spent_on < :to
                GROUP BY spent_on ORDER BY spent_on""", params));
        return out;
    }

    private static YearMonth parseMonth(String month) {
        try {
            return month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_MONTH", "month must look like 2026-10");
        }
    }

    private static void validate(ExpenseRequest r) {
        if (r.amount() == null || r.amount().signum() < 0 || r.amount().compareTo(new BigDecimal("10000000")) > 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "amount must be between 0 and 10,000,000");
        }
        if (r.category() == null || !CATEGORIES.contains(r.category())) {
            throw ApiException.badRequest("INVALID_CATEGORY", "Unknown category");
        }
        if (r.spentOn() == null || r.spentOn().isAfter(LocalDate.now().plusDays(1))) {
            throw ApiException.badRequest("INVALID_DATE", "spentOn is required and cannot be in the future");
        }
    }

    private static Map<String, Object> params(ExpenseRequest r) {
        return p("me", Ctx.membershipId(), "amount", r.amount(), "cat", r.category(), "on", r.spentOn(),
                "note", TextSanitizer.truncate(TextSanitizer.cleanSingleLine(r.note()), 200));
    }
}
