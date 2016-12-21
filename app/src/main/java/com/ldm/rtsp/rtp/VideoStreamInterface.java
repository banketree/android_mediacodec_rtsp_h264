package com.ldm.rtsp.rtp;

public interface VideoStreamInterface {
    void onVideoStream(byte[] var1);
    void releaseResource();
}