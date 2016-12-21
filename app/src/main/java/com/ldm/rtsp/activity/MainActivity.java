package com.ldm.rtsp.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.ldm.rtsp.R;
import com.ldm.rtsp.utils.Constant;


public class MainActivity extends Activity {
    private EditText rtsp_edt;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        rtsp_edt = (EditText) findViewById(R.id.rtsp_edt);
        findViewById(R.id.rtsp_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //视频流地址，例如：rtsp://192.168.1.168:80/0
                String url = rtsp_edt.getText().toString().trim();
                if (TextUtils.isEmpty(url) || !url.startsWith("rtsp://")) {
                    Toast.makeText(MainActivity.this, "RTSP视频流地址错误！", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(MainActivity.this, RtspActivity.class);
                intent.putExtra(Constant.RTSP_URL, url);
                startActivity(intent);
            }
        });
        findViewById(R.id.local_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, LocalH264Activity.class));
            }
        });
    }
}