package net.rose.saturnProtect.db;

public final class TooManyRecordsException extends RuntimeException {

    private final long count;
    private final int max;

    public TooManyRecordsException(long count, int max) {
        super("Matched " + count + " records, the limit is " + max);
        this.count = count;
        this.max = max;
    }

    public long count() {
        return count;
    }

    public int max() {
        return max;
    }
}
