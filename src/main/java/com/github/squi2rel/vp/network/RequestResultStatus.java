package com.github.squi2rel.vp.network;

public enum RequestResultStatus {
    OK(0),
    DENIED(1),
    ERROR(2);

    public final int id;

    RequestResultStatus(int id) {
        this.id = id;
    }

    public static RequestResultStatus fromId(int id) {
        for (RequestResultStatus status : values()) {
            if (status.id == id) return status;
        }
        return ERROR;
    }
}
