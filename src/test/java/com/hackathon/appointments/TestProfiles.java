package com.hackathon.appointments;

import org.springframework.test.context.ActiveProfilesResolver;

/**
 * Lets the same test suite run against either database:
 * <pre>
 *   mvn test                                           # SQLite (default)
 *   mvn test -Dtest.profiles=test,postgres,pgtest      # PostgreSQL
 * </pre>
 */
public class TestProfiles implements ActiveProfilesResolver {
    @Override
    public String[] resolve(Class<?> testClass) {
        return System.getProperty("test.profiles", "test").split(",");
    }
}
