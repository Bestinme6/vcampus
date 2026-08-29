package com.vcampus.server.database;

import com.vcampus.server.database.LibraryCatalogStore.CatalogItem;
import com.vcampus.server.database.LibraryCatalogStore.CatalogPage;
import com.vcampus.server.database.LibraryLoanStore.LoanPage;
import com.vcampus.server.database.LibraryLoanStore.LoanRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

import static com.vcampus.common.model.LibraryLoanChannel.SELF_SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LibraryStoreContractTest {
    @Test
    void catalogPageDefensivelyCopiesRows() {
        var source = new ArrayList<CatalogItem>();
        source.add(new CatalogItem(10L, "9787111565277", "并发编程", "作者", "出版社",
                2020, "计算机", "简介", true, 4, 2));

        CatalogPage page = new CatalogPage(source, 1, 10, 1);
        source.clear();

        assertEquals(1, page.rows().size());
        assertThrows(UnsupportedOperationException.class, () -> page.rows().clear());
    }

    @Test
    void loanPageDefensivelyCopiesRows() {
        var source = new ArrayList<LoanRecord>();
        source.add(new LoanRecord(501L, 10L, "9787111565277", "并发编程", 101L,
                "B000000128", "2026000001", "张同学", Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-31T00:00:00Z"), 0, null, null, SELF_SERVICE,
                false, true));

        LoanPage page = new LoanPage(source, 1, 10, 1);
        source.clear();

        assertEquals(1, page.rows().size());
        assertThrows(UnsupportedOperationException.class, () -> page.rows().clear());
    }
}
