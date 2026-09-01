package com.vcampus.common.model;

/** Wire vocabulary; SQL ordering is selected by the server, never supplied as SQL. */
public enum LibrarySort {
    CODE_ASC, CODE_DESC, TITLE_ASC, TITLE_DESC,
    CATEGORY_ASC, CATEGORY_DESC, BORROW_COUNT_ASC, BORROW_COUNT_DESC
}
