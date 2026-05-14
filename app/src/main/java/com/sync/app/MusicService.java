package com.sync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.InputStream;
import java.net.URL;

public class MusicService extends Service {

    public static final String ACTION_PLAY   = "com.sync.app.PLAY";
    public static final String ACTION_PAUSE  = "com.sync.app.PAUSE";
    public static final String ACTION_NEXT   = "com.sync.app.NEXT";
    public static final String ACTION_PREV   = "com.sync.app.PREV";
    public static final String ACTION_STOP   = "com.sync.app.STOP";
    public static final String ACTION_UPDATE = "com.sync.app.UPDATE";

    private static final String CHANNEL_ID = "sync_music_channel";
    private static final int    NOTIF_ID   = 1001;

    private MediaSessionCompat mediaSession;
    private NotificationManager notifManager;
    private final IBinder binder = new LocalBinder();

    // 현재 트랙 정보
    private String currentTitle   = "SYNC";
    private String currentArtist  = "";
    private String currentThumbUrl = "";
    private boolean isPlaying = false;

    // MainActivity 콜백
    private ServiceCallback callback;

    public interface ServiceCallback {
        void onPlay();
        void onPause();
        void onNext();
        void onPrev();
        void onStop();
    }

    public class LocalBinder extends Binder {
        MusicService getService() { return MusicService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initMediaSession();

        // 브로드캐스트 수신 (알림 버튼 클릭)
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREV);
        filter.addAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(controlReceiver, filter);
        }
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "SYNCMediaSession");
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()     { if (callback != null) callback.onPlay(); }
            @Override public void onPause()    { if (callback != null) callback.onPause(); }
            @Override public void onSkipToNext()     { if (callback != null) callback.onNext(); }
            @Override public void onSkipToPrevious() { if (callback != null) callback.onPrev(); }
            @Override public void onStop()     { if (callback != null) callback.onStop(); }
        });
        mediaSession.setActive(true);
    }

    // 알림 버튼 클릭 수신
    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case ACTION_PLAY:   if (callback != null) callback.onPlay();   break;
                case ACTION_PAUSE:  if (callback != null) callback.onPause();  break;
                case ACTION_NEXT:   if (callback != null) callback.onNext();   break;
                case ACTION_PREV:   if (callback != null) callback.onPrev();   break;
                case ACTION_STOP:
                    if (callback != null) callback.onStop();
                    stopSelf();
                    break;
            }
        }
    };

    // MainActivity에서 호출: 트랙 정보 업데이트
    public void updateTrack(String title, String artist, String thumbUrl, boolean playing) {
        this.currentTitle    = title;
        this.currentArtist   = artist;
        this.currentThumbUrl = thumbUrl;
        this.isPlaying       = playing;
        updatePlaybackState(playing);
        updateMetadata(title, artist);
        // 썸네일은 비동기로 로드
        new Thread(() -> {
            Bitmap bmp = loadBitmap(thumbUrl);
            showNotification(bmp);
        }).start();
    }

    // 재생 상태만 업데이트 (썸네일 재로드 없이)
    public void updatePlayState(boolean playing) {
        this.isPlaying = playing;
        updatePlaybackState(playing);
        new Thread(() -> {
            Bitmap bmp = loadBitmap(currentThumbUrl);
            showNotification(bmp);
        }).start();
    }

    private void updatePlaybackState(boolean playing) {
        PlaybackStateCompat state = new PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY |
                PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                PlaybackStateCompat.ACTION_STOP)
            .setState(
                playing ? PlaybackStateCompat.STATE_PLAYING
                        : PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build();
        mediaSession.setPlaybackState(state);
    }

    private void updateMetadata(String title, String artist) {
        MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .build();
        mediaSession.setMetadata(metadata);
    }

    private void showNotification(Bitmap albumArt) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 버튼 PendingIntent 생성 헬퍼
        PendingIntent prevPI  = makePendingIntent(ACTION_PREV, 1);
        PendingIntent playPI  = makePendingIntent(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 2);
        PendingIntent nextPI  = makePendingIntent(ACTION_NEXT, 3);
        PendingIntent closePI = makePendingIntent(ACTION_STOP, 4);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist.isEmpty() ? "SYNC" : currentArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            // 이전 곡
            .addAction(android.R.drawable.ic_media_previous, "이전", prevPI)
            // 재생/일시정지
            .addAction(
                isPlaying ? android.R.drawable.ic_media_pause
                          : android.R.drawable.ic_media_play,
                isPlaying ? "일시정지" : "재생", playPI)
            // 다음 곡
            .addAction(android.R.drawable.ic_media_next, "다음", nextPI)
            // 닫기
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "닫기", closePI)
            // MediaStyle 적용 (잠금화면 등에서 미디어 UI 표시)
            .setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2)); // 이전/재생/다음

        if (albumArt != null) builder.setLargeIcon(albumArt);

        Notification notification = builder.build();
        startForeground(NOTIF_ID, notification);
    }

    private PendingIntent makePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Bitmap loadBitmap(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return null;
        try {
            InputStream in = new URL(urlStr).openStream();
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) { return null; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "SYNC 음악 재생",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("음악 재생 중 알림");
            channel.setShowBadge(false);
            notifManager.createNotificationChannel(channel);
        }
    }

    public void setCallback(ServiceCallback cb) { this.callback = cb; }
    public MediaSessionCompat getMediaSession() { return mediaSession; }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 서비스가 강제종료 후 재시작될 때 알림 유지
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        stopForeground(true);
    }
}
