package net.rose.saturnProtect.model;

import java.util.List;

public record LookupResult(long total, List<BlockRecord> records) {
}
