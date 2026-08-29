package com.vcampus.common.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryPolicyTest {
    @Test
    void studentAndTeacherBorrowWhileOnlyLibraryAdministratorsManage() {
        assertTrue(LibraryAccessPolicy.canBorrow(Set.of(UserRole.STUDENT)));
        assertTrue(LibraryAccessPolicy.canBorrow(Set.of(UserRole.TEACHER, UserRole.LIBRARY_ADMIN)));
        assertFalse(LibraryAccessPolicy.canBorrow(Set.of(UserRole.SUPER_ADMIN)));
        assertTrue(LibraryAccessPolicy.canManage(Set.of(UserRole.LIBRARY_ADMIN)));
        assertTrue(LibraryAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
        assertFalse(LibraryAccessPolicy.canManage(Set.of(UserRole.STUDENT)));
    }

    @Test
    void accessPolicyRejectsNullRoleSets() {
        assertThrows(NullPointerException.class, () -> LibraryAccessPolicy.canBorrow(null));
        assertThrows(NullPointerException.class, () -> LibraryAccessPolicy.canManage(null));
    }

    @Test
    void normalizesChecksumValidIsbnAndCampusBarcode() {
        assertEquals("9787111565277", LibraryCodePolicy.normalizeIsbn("978-7-111-56527-7"));
        assertEquals("0306406152", LibraryCodePolicy.normalizeIsbn("0-306-40615-2"));
        assertEquals("097522980X", LibraryCodePolicy.normalizeIsbn("0-9752298-0-x"));
        assertEquals("B000000128", LibraryCodePolicy.requireValidBarcode(" B000000128 "));
    }

    @Test
    void rejectsMalformedOrChecksumInvalidCodes() {
        assertThrows(IllegalArgumentException.class, () -> LibraryCodePolicy.normalizeIsbn(null));
        assertThrows(IllegalArgumentException.class, () -> LibraryCodePolicy.normalizeIsbn("9787111565272"));
        assertThrows(IllegalArgumentException.class, () -> LibraryCodePolicy.normalizeIsbn("1234567890"));
        assertThrows(IllegalArgumentException.class, () -> LibraryCodePolicy.requireValidBarcode("B128"));
        assertThrows(IllegalArgumentException.class, () -> LibraryCodePolicy.requireValidBarcode("b000000128"));
    }
}
