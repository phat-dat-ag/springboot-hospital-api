package com.hospitalmanagement.hospital.encounter.service;

import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;

/**
 * Explicitly bounded learning workflow, independent of persistence.
 */
public final class EncounterTransitions {
    private EncounterTransitions() {
    }

    public static boolean allows(EncounterStatus current, EncounterStatus target) {
        if (current == null || target == null) {
            return false;
        }

        return switch (current) {
            case SCHEDULED -> target == EncounterStatus.ARRIVED
                    || target == EncounterStatus.CANCELLED;
            case ARRIVED -> target == EncounterStatus.IN_PROGRESS
                    || target == EncounterStatus.CANCELLED;
            case IN_PROGRESS, COMPLETED, CANCELLED, ENTERED_IN_ERROR -> false;
        };
    }
}

