package com.ldm.rtsp.activity;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.ldm.rtsp.R;
import com.ldm.rtsp.rtp.H264StreamInterface;
import com.ldm.rtsp.rtp.TCP4RtspUtil;
import com.ldm.rtsp.rtp.VideoStreamImpl;
import com.ldm.rtsp.rtsp.RtspDecoder;
import com.ldm.rtsp.utils.Constant;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

//RTSP协议实时播放播放H264视频
public class RtspActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private Socket mSocket;
    private String sessionId;
    private SurfaceView mSurfaceView;
    static boolean previewing = false;

    public static boolean getPreviewStatus() {
        return previewing;
    }

    private boolean isFirst = true;
    private int mCount = 0;
    //边播放边保存到SD卡文件目录
    private static final String filePath = Environment
            .getExternalStorageDirectory() + "/test.h264";
    Handler handler = new Handler();

    File encodedFile = new File(filePath);
    InputStream is;
    private RtspDecoder mPlayer = null;
    private String rtsp_url;
    private TCP4RtspUtil client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_rtsp);
        mSurfaceView = (SurfaceView) findViewById(R.id.surfaceview);
        mSurfaceView.getHolder().addCallback(this);
        rtsp_url = getIntent().getStringExtra(Constant.RTSP_URL);
    }

    private void getRtspStream() throws Exception {
        client = new TCP4RtspUtil(rtsp_url, new VideoStreamImpl(new H264StreamInterface() {
            private OutputStream out = new FileOutputStream(filePath);

            public void process(byte[] stream) {
                try {
                    this.out.write(stream);
                    onReceiveVideoData(stream);
                } catch (Exception e) {
                    e.printStackTrace();
                    try {
                        out.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }

            }
        }));
        client.doStart();
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    getRtspStream();
                    //调用播放开关
                    client.play();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
        //初始化实时流解码器
        mPlayer = new RtspDecoder(holder.getSurface(), 0);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
        Log.d("DecodeActivity", "in surfaceChanged");

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        //关闭操作
        if (mPlayer != null) {
            mPlayer.stopRunning();
            mPlayer = null;
        }
    }

    private void onReceiveVideoData(byte[] video) {
        if (isFirst) {
            if (video[4] == 0x67) {
                byte[] tmp = new byte[15];
                //把video中索引0开始的15个数字复制到tmp中索引为0的位置上
                System.arraycopy(video, 0, tmp, 0, 15);
                try {
                    mPlayer.initial(tmp);
                } catch (Exception e) {
                    return;
                }
            } else {
                return;
            }
            isFirst = false;

        }

        if (mPlayer != null)
            mPlayer.setVideoData(video);

    }
}
