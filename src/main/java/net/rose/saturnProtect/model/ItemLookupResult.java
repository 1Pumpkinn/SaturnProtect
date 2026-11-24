package net.rose.saturnProtect.model;

import java.util.List;

public record ItemLookupResult(long total, List<ItemRecord> records) {
}
