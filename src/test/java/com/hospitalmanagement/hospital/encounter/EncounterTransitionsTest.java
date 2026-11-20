package com.hospitalmanagement.hospital.encounter;

import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;
import com.hospitalmanagement.hospital.encounter.service.EncounterTransitions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EncounterTransitionsTest {
    private static final Set<String> ALLOWED = Set.of(
            "scheduled:arrived",
            "scheduled:cancelled",
            "arrived:in_progress",
            "arrived:cancelled"
    );

    @ParameterizedTest
    @MethodSource("statusPairs")
    void enforcesEveryPairInTheBoundedTransitionTable(StatusPair pair) {
        String key = pair.current().getValue() + ":" + pair.target().getValue();

        assertThat(EncounterTransitions.allows(pair.current(), pair.target()))
                .isEqualTo(ALLOWED.contains(key));
    }

    @Test
    void rejectsMissingStates() {
        assertThat(EncounterTransitions.allows(null, EncounterStatus.ARRIVED)).isFalse();
        assertThat(EncounterTransitions.allows(EncounterStatus.SCHEDULED, null)).isFalse();
    }

    static Stream<StatusPair> statusPairs() {
        return Stream.of(EncounterStatus.values())
                .flatMap(current -> Stream.of(EncounterStatus.values())
                        .map(target -> new StatusPair(current, target))
                );
    }

    record StatusPair(EncounterStatus current, EncounterStatus target) {
    }
}

