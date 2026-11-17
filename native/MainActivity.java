package com.imal.drivora;

import android.os.Bundle;
import android.view.WindowManager;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ObdLinkPlugin.class);
        registerPlugin(MediaLibPlugin.class);
        registerPlugin(VoicePlugin.class);
        registerPlugin(PlayerPlugin.class);
        super.onCreate(savedInstanceState);
        /* a dashboard must never let the screen sleep */
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
