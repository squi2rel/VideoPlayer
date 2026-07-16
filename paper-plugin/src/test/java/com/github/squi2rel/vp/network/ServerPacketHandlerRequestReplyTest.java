package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.i18n.VpTranslation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPacketHandlerRequestReplyTest {
    @Test
    void firstTerminalResultWins() {
        ArrayList<RequestResultStatus> statuses = new ArrayList<>();
        ServerPacketHandler.RequestReply reply = new ServerPacketHandler.RequestReply((status, message) -> statuses.add(status));

        reply.ok();
        reply.denied(VpTranslation.literal("denied"));
        reply.error(VpTranslation.literal("error"));
        reply.finish();

        assertEquals(java.util.List.of(RequestResultStatus.OK), statuses);
        assertTrue(reply.completed());
    }

    @Test
    void incompleteSynchronousRequestGetsOneError() {
        ArrayList<RequestResultStatus> statuses = new ArrayList<>();
        ServerPacketHandler.RequestReply reply = new ServerPacketHandler.RequestReply((status, message) -> statuses.add(status));

        reply.finish();
        reply.finish();

        assertEquals(java.util.List.of(RequestResultStatus.ERROR), statuses);
        assertTrue(reply.completed());
    }

    @Test
    void deferredRequestWaitsForOneAsynchronousResult() {
        ArrayList<RequestResultStatus> statuses = new ArrayList<>();
        ServerPacketHandler.RequestReply reply = new ServerPacketHandler.RequestReply((status, message) -> statuses.add(status));

        reply.defer();
        reply.finish();
        assertFalse(reply.completed());
        assertTrue(statuses.isEmpty());

        reply.error(VpTranslation.literal("failed"));
        reply.ok();

        assertEquals(java.util.List.of(RequestResultStatus.ERROR), statuses);
        assertTrue(reply.completed());
    }
}
