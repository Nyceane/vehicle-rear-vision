package rearvision.vehicle.vehiclerearvision;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.ShapeDrawable;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements IVLCVout.Callback, LibVLC.OnNativeCrashListener {
    public final static String TAG = "LibVLCAndroid/Stream";

    /** Called when the activity is first created. */
    ShapeDrawable mDrawable = new ShapeDrawable();
    public static int x;
    public static int y;

    //for video
    private String mFilePath;

    // display surface
    private SurfaceView mSurface;
    private SurfaceHolder holder;

    // media player
    private LibVLC libvlc;
    private MediaPlayer mMediaPlayer = null;
    private int mVideoWidth;
    private int mVideoHeight;

    DatagramSocket mSocket;
    Thread udpConnect;
    TextView mTxtDistance;

    //Used for server ip
    String gatewayIp;

    //line tracing
    ImageView traceLine;
    Bitmap bitmap;
    Canvas canvas;
    Paint paint;
    int LineLength = 70;
    int height,width;

    /**
     * Broadcast receiver is used to make sure wifi is connected
     */
    private BroadcastReceiver myWifiReceiver
            = new BroadcastReceiver(){

        @Override
        public void onReceive(Context context, Intent intent) {

            NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            WifiManager wifiManager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);

            ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

            if(info != null && info.isConnected() && mWifi != null && mWifi.isConnected()) {
                // Do your work.

                // e.g. To check the Network Name or other info:
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                String ssid = wifiInfo.getSSID();
                Log.d("ssid", ssid);
                if(ssid.contains("vehicle-rear-vision"))
                {
                    DhcpInfo d = wifiManager.getDhcpInfo();
                    gatewayIp = Formatter.formatIpAddress(d.gateway);
                    startApp();
                }
            }
        }
    };

    /**
     * We need the router ip in order to stream video as well as getting information from Walabot
     */
    private void startApp()
    {
        // Receive path to play from intent
        mFilePath = "http://" + gatewayIp + ":5001/";
        Log.d(TAG, "Playing back " + mFilePath);
        holder = mSurface.getHolder();

        udpConnect = new Thread(new ClientListen());
        udpConnect.start();

        setupTraceline();
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mSurface = (SurfaceView) findViewById(R.id.surfView);
        traceLine = (ImageView)findViewById(R.id.traceLine);
        mTxtDistance = (TextView)findViewById(R.id.txtDistance);

        //Register receiver for Wifi
        this.registerReceiver(this.myWifiReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        connectToWifi();
    }

    /**
     * Traceline used for backing up
     */
    private void setupTraceline()
    {
        CreateBitmap();
        CreateCanvas();
        CreatePaint();

        height = canvas.getHeight();
        width = canvas.getWidth();

        DrawLine();
        traceLine.setImageBitmap(bitmap);

    }

    public void CreateBitmap(){

        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);

        bitmap = Bitmap.createBitmap(
                size.x - size.x * 1/2,
                size.y - size.y * 6/7,
                Bitmap.Config.ARGB_8888
        );

    }

    public void CreateCanvas(){

        canvas = new Canvas(bitmap);

        canvas.drawColor(Color.TRANSPARENT);

    }

    public void CreatePaint(){

        paint = new Paint();

        paint.setStyle(Paint.Style.FILL);

        paint.setColor(Color.WHITE);

        paint.setAntiAlias(true);
        paint.setStrokeWidth(10);
    }

    public void DrawLine(){

        canvas.drawLine(
                width * 1/5,
                0,
                0,
                height,
                paint
        );

        canvas.drawLine(
                width * 4/5,
                0,
                width,
                height,
                paint
        );

    }


    /**
     * The thread is used to listen to port 5002 in UDP from Intel Joule, this is mainly for data transfer
     * from Walabot
     */
    public class ClientListen implements Runnable {
        @Override
        public void run() {
            boolean run = true;
            try {
                mSocket = new DatagramSocket(5002, InetAddress.getByName("0.0.0.0"));

                while (run) {
                    byte[] message = new byte[20];
                    DatagramPacket packet = new DatagramPacket(message,message.length);
//                    Log.i("UDP client: ", "about to wait to receive");
                    mSocket.receive(packet);
                    String text = new String(message, 0, packet.getLength());
//                    Log.d("Received data", text);

                    final Float number = Float.valueOf(text.replace("\"", ""));

                    runOnUiThread(new Runnable() {

                        @Override
                        public void run() {
                            if(number < 16)
                            {
                                //This is just a number from walabot for being too low,
                                //false postives and default number walabot seems to put out
                                mTxtDistance.setText("");
                            }
                            else if(number >= 16 && number <= 22)
                            {
                                //Below 22cm is just too close
                                mTxtDistance.setText("too close");
                                mTxtDistance.setTextColor(Color.RED);
                            }
                            else
                            {
                                mTxtDistance.setText(String.format("%.2f", number) + "cm");
                                mTxtDistance.setTextColor(Color.WHITE);
                            }
                        }
                    });

                }
            }catch (IOException e) {
                Log.e("UDP", "error: ", e);
                run = false;
            }
        }
    }

    /**
     * When starting the app, we need to first connect to the IoT device via wifi port
     */
    private void connectToWifi()
    {
        if(checkWifiSet())
        {
            startApp();
            return;
        }

        String networkSSID = "vehicle-rear-vision";
        String networkPass = "safedriving";

        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + networkSSID + "\"";
        conf.preSharedKey = "\""+ networkPass +"\"";


        WifiManager wifiManager = (WifiManager)this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiManager.addNetwork(conf);

        List<WifiConfiguration> list = wifiManager.getConfiguredNetworks();
        for( WifiConfiguration i : list ) {
            if(i.SSID != null && i.SSID.equals("\"" + networkSSID + "\"")) {
                wifiManager.disconnect();
                wifiManager.enableNetwork(i.networkId, true);
                return;
            }
        }

        Toast.makeText(this.getApplicationContext(), "", Toast.LENGTH_LONG).show();
        this.finish();
    }

    /**
     * This is mainly used for debugging where wifi doesn't
     * @return
     */
    private boolean checkWifiSet()
    {
        ConnectivityManager connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        if(mWifi != null && mWifi.isConnected()) {
            // Do your work.
            WifiManager wifiManager = (WifiManager)this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

            // e.g. To check the Network Name or other info:
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            String ssid = wifiInfo.getSSID();
            if(ssid.contains("vehicle-rear-vision"))
            {
                DhcpInfo d = wifiManager.getDhcpInfo();
                gatewayIp = Formatter.formatIpAddress(d.gateway);
                return true;
            }
        }

        return false;
    }


    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setSize(mVideoWidth, mVideoHeight);
    }

    @Override
    protected void onResume() {
        super.onResume();
        createPlayer(mFilePath);
    }

    @Override
    protected void onPause() {
        super.onPause();
        releasePlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //getting rid of receiver
        this.unregisterReceiver(this.myWifiReceiver);

        //Closing the socket on udp
        if(mSocket != null)
        {
            mSocket.close();
        }

        //get rid of the player
        releasePlayer();
    }

    private void setSize(int width, int height) {
        mVideoWidth = width;
        mVideoHeight = height;
        if (mVideoWidth * mVideoHeight <= 1)
            return;

        if(holder == null || mSurface == null)
            return;

        // get screen size
        int w = getWindow().getDecorView().getWidth();
        int h = getWindow().getDecorView().getHeight();

        // getWindow().getDecorView() doesn't always take orientation into
        // account, we have to correct the values
        boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        if (w > h && isPortrait || w < h && !isPortrait) {
            int i = w;
            w = h;
            h = i;
        }

        float videoAR = (float) mVideoWidth / (float) mVideoHeight;
        float screenAR = (float) w / (float) h;

        if (screenAR < videoAR)
            h = (int) (w / videoAR);
        else
            w = (int) (h * videoAR);

        // force surface buffer size
        holder.setFixedSize(mVideoWidth, mVideoHeight);

        // set display size
        LayoutParams lp = mSurface.getLayoutParams();
        lp.width = w;
        lp.height = h;
        mSurface.setLayoutParams(lp);
        mSurface.invalidate();
    }

    private void createPlayer(String media) {
        releasePlayer();
        try {
            if (media.length() > 0) {
                Toast toast = Toast.makeText(this, media, Toast.LENGTH_LONG);
                toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0,
                        0);
                toast.show();
            }

            // Create LibVLC
            // TODO: make this more robust, and sync with audio demo
            ArrayList<String> options = new ArrayList<>();
            //options.add("--subsdec-encoding <encoding>");
            options.add("-vvv"); // verbosity
            libvlc = new LibVLC(options);
            libvlc.setOnNativeCrashListener(this);
            holder.setKeepScreenOn(true);

            // Create media player
            mMediaPlayer = new MediaPlayer(libvlc);
            mMediaPlayer.setEventListener(mPlayerListener);

            // Set up video output
            final IVLCVout vout = mMediaPlayer.getVLCVout();
            vout.setVideoView(mSurface);
            //vout.setSubtitlesView(mSurfaceSubtitles);
            vout.addCallback(this);
            vout.attachViews();

            Media m = new Media(libvlc, Uri.parse(media));
            mMediaPlayer.setMedia(m);
            mMediaPlayer.play();
        } catch (Exception e) {
            Toast.makeText(this, "Error creating player!", Toast.LENGTH_LONG).show();
        }
    }

    // TODO: handle this cleaner
    private void releasePlayer() {
        if (libvlc == null)
            return;
        mMediaPlayer.stop();
        final IVLCVout vout = mMediaPlayer.getVLCVout();
        vout.removeCallback(this);
        vout.detachViews();
        holder = null;
        libvlc.release();
        libvlc = null;

        mVideoWidth = 0;
        mVideoHeight = 0;
    }

    private MediaPlayer.EventListener mPlayerListener = new MyPlayerListener(this);

    @Override
    public void onNewLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        if (width * height == 0)
            return;

        // store video size
        mVideoWidth = width;
        mVideoHeight = height;
        setSize(mVideoWidth, mVideoHeight);
    }

    @Override
    public void onHardwareAccelerationError(IVLCVout vlcVout) {
        // Handle errors with hardware acceleration
        Log.e(TAG, "Error with hardware acceleration");
        this.releasePlayer();
        Toast.makeText(this, "Error with hardware acceleration", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onSurfacesCreated(IVLCVout vout) {

    }

    @Override
    public void onSurfacesDestroyed(IVLCVout vout) {

    }

    private static class MyPlayerListener implements MediaPlayer.EventListener {
        private WeakReference<MainActivity> mOwner;

        public MyPlayerListener(MainActivity owner) {
            mOwner = new WeakReference<>(owner);
        }

        @Override
        public void onEvent(MediaPlayer.Event event) {
            MainActivity player = mOwner.get();

            switch(event.type) {
                case MediaPlayer.Event.EndReached:
                    Log.d(TAG, "MediaPlayerEndReached");
                    player.releasePlayer();
                    break;
                case MediaPlayer.Event.Playing:
                case MediaPlayer.Event.Paused:
                case MediaPlayer.Event.Stopped:
                default:
                    break;
            }
        }
    }

    @Override
    public void onNativeCrash() {
        // Handle errors with hardware acceleration
        Log.e(TAG, "Native Crash");
        this.releasePlayer();
        Toast.makeText(this, "Native Crash", Toast.LENGTH_LONG).show();
    }
}