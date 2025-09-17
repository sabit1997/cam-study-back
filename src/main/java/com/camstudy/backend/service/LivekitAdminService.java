package com.camstudy.backend.service;

import java.io.IOException;

import org.springframework.stereotype.Service;

import io.livekit.server.RoomServiceClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LivekitAdminService {

    private final RoomServiceClient roomClient;

    public void kick(String roomName, String identity) {
        try {
            roomClient.removeParticipant(roomName, identity).execute();
        } catch (IOException e) {
            throw new RuntimeException("livekit removeParticipant failed", e);
        }
    }

    public void setPublishPermission(String roomName, String identity, boolean canPublish) {
        try {
            livekit.LivekitModels.ParticipantPermission perms =
                livekit.LivekitModels.ParticipantPermission.newBuilder()
                    .setCanPublish(canPublish)
                    .setCanSubscribe(true)
                    .build();

            // ✅ name=null, metadata=null, permission=perms (총 5개 인자)
            roomClient.updateParticipant(
                roomName,
                identity,
                null,           // name
                null,           // metadata
                perms           // permission
            ).execute();

        } catch (IOException e) {
            throw new RuntimeException("livekit updateParticipant failed", e);
        }
    }
}
