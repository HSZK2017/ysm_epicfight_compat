package com.ysmef.compat.network;

/** The server's answer to a client asking about one model package. Sent as its ordinal. */
public enum ModelTransferStatus {
    /** The server has the package and will send it on request; hash and size are set. */
    AVAILABLE("available"),
    /** The client is the integrated server's own player: both read the same files. */
    SAME_FILES("same files as the server"),
    /** The server has no package of that id. */
    NOT_FOUND("the server does not have this model either"),
    /** The package is in YSM's auth folder and the server does not share those. */
    AUTH_RESTRICTED("it is an auth model and the server does not share auth models (modelTransfer.shareAuthModels)"),
    /** The package is over the server's size limit. */
    TOO_LARGE("it is larger than the server's limit (modelTransfer.maxModelSizeMiB)"),
    /** Model transfer is switched off on the server. */
    DISABLED("model transfer is disabled on the server (modelTransfer.enabled)"),
    /** The server could not read or hash the package. */
    FAILED("the server could not read it");

    private final String description;

    ModelTransferStatus(String description) {
        this.description = description;
    }

    /** Why this answer means the model will or will not arrive, for log lines. */
    public String description() {
        return description;
    }

    /** Decode a received ordinal; anything out of range reads as FAILED. */
    public static ModelTransferStatus fromOrdinal(int ordinal) {
        ModelTransferStatus[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : FAILED;
    }
}
