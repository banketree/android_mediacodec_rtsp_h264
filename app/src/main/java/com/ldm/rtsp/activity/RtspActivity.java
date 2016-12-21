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
import com.ldm.rtsp.rtp.RtpRequestModel;
import com.ldm.rtsp.rtp.RtpResponseModel;
import com.ldm.rtsp.rtp.VideoStreamImpl;
import com.ldm.rtsp.rtp.TCP4RtspUtil;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_rtsp);
        mSurfaceView = (SurfaceView) findViewById(R.id.surfaceview);
        mSurfaceView.getHolder().addCallback(this);
        rtsp_url = getIntent().getStringExtra(Constant.RTSP_URL);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    getRtspStream();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void getRtspStream() throws Exception {
        TCP4RtspUtil client = new TCP4RtspUtil(Constant.SERVER_IP, Constant.SERVER_PORT, new VideoStreamImpl(new H264StreamInterface() {
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
        /**
         * RTSP OPTION请求，目的是得到服务器提供什么方法。
         * RTSP提供的方法一般包括OPTIONS、DESCRIBE、SETUP、TEARDOWN、PLAY、PAUSE、SCALE、GET_PARAMETER
         */
        RtpRequestModel options = new RtpRequestModel();
        options.setMethod("OPTIONS");
        options.setUri(rtsp_url);
        options.setVersion("RTSP/1.0");
        RtpResponseModel resp1 = client.options(options, 15000L);
        Log.e(Constant.LOG_TAG, resp1.toString());
        /**
         * RTSP DESCRIBE请求，服务器收到的信息主要有媒体的名字，解码类型，视频分辨率等描述
         * 目的是为了从服务器那里得到会话描述信息（SDP）
         */
        RtpRequestModel describe = new RtpRequestModel();
        describe.setMethod("DESCRIBE");
        describe.setUri(rtsp_url);
        describe.setVersion("RTSP/1.0");
        describe.getHeaders().put("Accept", "application/sdp");
        RtpResponseModel resp2 = client.describe(options, 15000L);
        Log.e(Constant.LOG_TAG, resp2.toString());
        /**
         * RTSP SETUP请求。
         * 目的是请求会话建立并准备传输。请求信息主要包括传输协议和客户端端口号。
         */
        RtpRequestModel setup = new RtpRequestModel();
        setup.setMethod("SETUP");
        setup.setUri(rtsp_url + "/trackID=0");
        setup.setVersion("RTSP/1.0");
        setup.getHeaders().put("Transport", "RTP/AVP/TCP;unicast;interleaved=2-3");
        RtpResponseModel resp3 = client.setup(setup, 15000L);
        Log.e(Constant.LOG_TAG, resp3.toString());
        String sessionId = ((String) resp3.getHeaders().get("session")).split(";")[0];
        /**
         * RTSP PLAY的请求
         * 目的是请求播放视频流
         */
        RtpRequestModel play = new RtpRequestModel();
        play.setMethod("PLAY");
        play.setUri(rtsp_url + "/trackID=0");
        play.setVersion("RTSP/1.0");
        play.getHeaders().put("Session", sessionId);
        play.getHeaders().put("Range", "npt=0.000-");
        client.play(play);
        Thread.sleep(30000L);
        client.doStop();
    }


    private void stop(String sessionId) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("TEARDOWN ").append(" ")
                .append(rtsp_url).append(" ")
                .append("RTSP/1.0").append("\r\n");
        builder.append("CSeq:").append(" ").append(7).append("\r\n");
        builder.append("Session:").append(" ").append(sessionId)
                .append("\r\n");
        builder.append("\r\n");
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
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
