package com.ldm.rtsp.rtp;

import java.io.IOException;

public interface RtspInterface {
    void doStart() throws IOException;
    void doStop();
}