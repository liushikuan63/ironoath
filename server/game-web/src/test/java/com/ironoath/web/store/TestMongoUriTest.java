package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestMongoUriTest {

    @Test
    void databaseUriKeepsTheOwnedServerAndConnectionOptions() {
        assertThat(TestMongo.uriForDatabase(
                "mongodb://127.0.0.1:27030/?serverSelectionTimeoutMS=3000", "ironoath_contract_owned"))
                .isEqualTo("mongodb://127.0.0.1:27030/ironoath_contract_owned?serverSelectionTimeoutMS=3000");
    }

    @Test
    void databaseUriRejectsAnAlreadyScopedOrInvalidRoot() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestMongo.uriForDatabase(
                        "mongodb://127.0.0.1:27030/already-scoped", "ironoath_contract_owned"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestMongo.uriForDatabase(
                        "mongodb://127.0.0.1:27030/?serverSelectionTimeoutMS=3000", "bad/name"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
