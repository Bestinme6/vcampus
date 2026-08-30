USE vcampus;

CREATE TABLE IF NOT EXISTS library_code_sequences (
    code_type VARCHAR(32) PRIMARY KEY,
    next_value BIGINT NOT NULL,
    CONSTRAINT chk_library_code_sequence_positive CHECK (next_value BETWEEN 1 AND 1000000000)
);

ALTER TABLE books
    ADD COLUMN catalog_code CHAR(11) NULL AFTER id,
    MODIFY COLUMN isbn VARCHAR(20) NULL;

CREATE TEMPORARY TABLE library_book_catalog_backfill AS
SELECT id AS book_id,
       ROW_NUMBER() OVER (ORDER BY id) AS sequence_value
  FROM books;

UPDATE books b
JOIN library_book_catalog_backfill m ON m.book_id = b.id
   SET b.catalog_code = CONCAT('BK', LPAD(m.sequence_value, 9, '0'))
 WHERE b.catalog_code IS NULL;

DROP TEMPORARY TABLE library_book_catalog_backfill;

ALTER TABLE books
    MODIFY COLUMN catalog_code CHAR(11) NOT NULL,
    ADD UNIQUE KEY uk_book_catalog_code (catalog_code),
    ADD CONSTRAINT chk_book_catalog_code CHECK (catalog_code REGEXP '^BK[0-9]{9}$');

INSERT INTO library_code_sequences (code_type, next_value)
SELECT 'BOOK_CATALOG', COALESCE(MAX(CAST(SUBSTRING(catalog_code, 3) AS UNSIGNED)), 0) + 1
  FROM books
ON DUPLICATE KEY UPDATE next_value = GREATEST(next_value, VALUES(next_value));

INSERT INTO library_code_sequences (code_type, next_value)
SELECT 'COPY_BARCODE', COALESCE(MAX(CAST(SUBSTRING(barcode, 2) AS UNSIGNED)), 0) + 1
  FROM book_copies
ON DUPLICATE KEY UPDATE next_value = GREATEST(next_value, VALUES(next_value));
