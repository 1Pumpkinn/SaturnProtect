package net.rose.saturnProtect.model;

import java.util.List;

public record EventLookupResult(long total, List<EventRecord> records) {
}
