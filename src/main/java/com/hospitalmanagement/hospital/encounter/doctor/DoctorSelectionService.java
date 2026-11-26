package com.hospitalmanagement.hospital.encounter.doctor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DoctorSelectionService {
    public static final int CANDIDATE_LIMIT = 20;

    private final DoctorCandidates candidates;
    private final DoctorEligibility eligibility;
    private final Clock clinicClock;

    public Page search(String name, UUID after, int size) {
        var window = candidates.scan(name.strip(), after, LocalDate.now(clinicClock),
                CANDIDATE_LIMIT + 1);
        var items = new ArrayList<Item>(size);
        int evaluated = 0;

        while (evaluated < Math.min(window.size(), CANDIDATE_LIMIT) && items.size() < size) {
            var candidate = window.get(evaluated++);

            if (eligibility.hasEligibleDoctorAccount(candidate.accountId())) {
                items.add(new Item(candidate.id(), candidate.displayName(), candidate.specialty()));
            }
        }

        UUID nextCursor = evaluated < window.size() ? window.get(evaluated - 1).id() : null;

        return new Page(List.copyOf(items), nextCursor);
    }

    public record Item(UUID id, String displayName, String primarySpecialty) {
    }

    public record Page(List<Item> items, UUID nextCursor) {
    }
}
