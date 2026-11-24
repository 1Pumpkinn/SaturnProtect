package net.rose.saturnProtect.model;

/** One line of a lookup: a block change or an event such as a kill. */
public sealed interface LookupEntry permits BlockRecord, EventRecord {
}
