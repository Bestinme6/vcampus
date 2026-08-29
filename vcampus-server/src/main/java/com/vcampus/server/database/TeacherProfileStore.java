package com.vcampus.server.database;

import com.vcampus.server.model.TeacherProfile;

import java.sql.SQLException;
import java.util.Optional;

public interface TeacherProfileStore {
    Optional<TeacherProfile> findByUserId(long userId) throws SQLException;

    boolean updateContact(long userId, String phone, String email) throws SQLException;
}
