package com.appletv.launcher;

final class AudioOutputRoute {
    private AudioOutputRoute() {
    }

    static boolean isCurrentTvOutput(boolean spotifyRemote, boolean spotifyDeviceIsCurrentTv,
                                     boolean bluetoothA2dpOn, boolean bluetoothScoOn,
                                     boolean wiredHeadsetOn) {
        return (!spotifyRemote || spotifyDeviceIsCurrentTv)
                && !bluetoothA2dpOn && !bluetoothScoOn && !wiredHeadsetOn;
    }
}
