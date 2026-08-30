USE vcampus;

ALTER TABLE library_loans
    DROP CHECK chk_library_return_condition,
    ADD CONSTRAINT chk_library_return_condition CHECK
        (return_condition IS NULL OR return_condition IN ('NORMAL', 'LOST', 'DAMAGED'));
