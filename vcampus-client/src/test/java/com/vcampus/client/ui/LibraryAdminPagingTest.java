package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryAdminPagingTest {
    @Test
    void exposesSecondPageWhenTotalExceedsPageSize() {
        LibraryAdminPaging paging = new LibraryAdminPaging();
        paging.update(1, 10, 11);

        assertFalse(paging.canPrevious());
        assertTrue(paging.canNext());
        assertEquals(2, paging.next());
        assertTrue(paging.canPrevious());
        assertFalse(paging.canNext());
        assertEquals("第 2 页 · 共 11 项", paging.label());
    }

    @Test
    void resetReturnsToFirstPageForNewSearchOrCreate() {
        LibraryAdminPaging paging = new LibraryAdminPaging();
        paging.update(3, 10, 30);

        paging.reset();

        assertEquals(1, paging.page());
    }
}
