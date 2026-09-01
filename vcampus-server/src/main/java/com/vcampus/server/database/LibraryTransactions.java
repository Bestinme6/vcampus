package com.vcampus.server.database;

import java.sql.*;

/** All circulation and reminder mutations lock their book before users, copies or loans.
 * READ_COMMITTED ensures reads after waiting for that lock see the committed winner.
 * No transaction may lock active loans belonging to other books for a quota count.
 */
final class LibraryTransactions {
    private LibraryTransactions() {}

    static boolean lockBook(Connection c, long bookId) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("SELECT enabled FROM books WHERE id=? FOR UPDATE")) {
            s.setLong(1,bookId);
            try(ResultSet r=s.executeQuery()) {
                if(!r.next()) throw new LibraryRuleException("书目不存在");
                return r.getBoolean(1);
            }
        }
    }

    static <T> T run(ConnectionFactory factory, Work<T> work) throws SQLException {
        try(Connection c=factory.openConnection()) {
            boolean autoCommit=c.getAutoCommit(); int isolation=c.getTransactionIsolation();
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try { T result=work.run(c); c.commit(); return result; }
            catch(SQLException|RuntimeException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(autoCommit); c.setTransactionIsolation(isolation); }
        }
    }
    @FunctionalInterface interface Work<T> { T run(Connection connection) throws SQLException; }
}
