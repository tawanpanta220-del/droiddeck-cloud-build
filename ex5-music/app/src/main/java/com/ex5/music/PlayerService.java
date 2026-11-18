package com.ex5.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plays local files with the platform decoder (no resampling or effects added by the app),
 * keeps playing while the screen shows another app, and answers steering-wheel media keys
 * through a MediaSession.
 */
public class PlayerService extends Service {
    interface Listener {
        void onPlayerChanged();
    }

    final class LocalBinder extends Binder {
        PlayerService service() {
            return PlayerService.this;
        }
    }

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Track> queue = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private int index = -1;
    private boolean prepared;
    private boolean resumeOnFocusGain;
    private String formatInfo = "";
    private String error = "";

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        session = new MediaSession(this, "EX5Music");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        session.setActive(true);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        abandonFocus();
        session.release();
        super.onDestroy();
    }

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    List<Track> queue() { return queue; }
    int index() { return index; }
    boolean isPlaying() { return player != null && prepared && player.isPlaying(); }
    String formatInfo() { return formatInfo; }
    String error() { return error; }
    int position() { return player != null && prepared ? player.getCurrentPosition() : 0; }
    int duration() { return player != null && prepared ? player.getDuration() : 0; }

    Track current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    void setQueue(List<Track> tracks, int start) {
        queue.clear();
        queue.addAll(tracks);
        playIndex(start);
    }

    void playIndex(int i) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        Track t = queue.get(i);
        releasePlayer();
        error = "";
        formatInfo = describe(t);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnPreparedListener(mp -> {
            prepared = true;
            play();
        });
        player.setOnCompletionListener(mp -> {
            if (index + 1 < queue.size()) playIndex(index + 1);
            else { pause(); seekTo(0); }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            error = "เล่นไฟล์นี้ไม่ได้ (เครื่องอาจไม่มีตัวถอดรหัส) code " + what + "/" + extra;
            prepared = false;
            notifyChanged();
            return true;
        });
        try {
            player.setDataSource(this, t.uri);
            player.prepareAsync();
        } catch (Exception e) {
            error = "เปิดไฟล์ไม่ได้: " + e.getMessage();
        }
        notifyChanged();
    }

    void play() {
        if (player == null) {
            if (!queue.isEmpty()) playIndex(Math.max(index, 0));
            return;
        }
        if (!prepared) return;
        if (!requestFocus()) return;
        player.start();
        startForegroundCompat();
        notifyChanged();
    }

    void pause() {
        if (player != null && prepared && player.isPlaying()) player.pause();
        // Stay in the foreground while paused so steering-wheel "play" still works later.
        notifyChanged();
    }

    void toggle() {
        if (isPlaying()) pause(); else play();
    }

    void next() {
        if (index + 1 < queue.size()) playIndex(index + 1);
    }

    void previous() {
        if (position() > 3000 || index <= 0) seekTo(0);
        else playIndex(index - 1);
    }

    void seekTo(int ms) {
        if (player != null && prepared) player.seekTo(ms);
        notifyChanged();
    }

    private String describe(Track t) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(this, t.uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                StringBuilder sb = new StringBuilder();
                sb.append(Links.isLosslessMime(mime) ? "LOSSLESS · " : "LOSSY · ");
                sb.append(mime.substring("audio/".length()).toUpperCase(Locale.ROOT));
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sb.append(String.format(Locale.ROOT, " · %.1f kHz", f.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000f));
                }
                if (f.containsKey("bits-per-sample")) {
                    sb.append(" · ").append(f.getInteger("bits-per-sample")).append("-bit");
                }
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    sb.append(" · ").append(f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).append("ch");
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // Fall through to an extension-based guess.
        } finally {
            ex.release();
        }
        return Links.isLosslessName(t.name) ? "LOSSLESS · " + Links.extension(t.name).toUpperCase(Locale.ROOT) : "";
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            // Navigation prompts and calls: pause rather than duck to keep the stream untouched.
            resumeOnFocusGain = isPlaying();
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocusGain) {
            resumeOnFocusGain = false;
            play();
        }
    };

    private boolean requestFocus() {
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(focusListener, main)
                        .build();
            }
            result = audio.requestAudioFocus(focusRequest);
        } else {
            result = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(focusListener);
        }
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            // Android 12+ can refuse a foreground start from the background; playback continues.
        }
    }

    private Notification buildNotification() {
        Track t = current();
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(t != null ? t.name : getString(R.string.app_name))
                .setContentText(formatInfo)
                .setContentIntent(pi)
                .setOngoing(true)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                .build();
    }

    private void updateSession() {
        Track t = current();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t != null ? t.name : "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, formatInfo)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position(), isPlaying() ? 1f : 0f)
                .build());
    }

    private void notifyChanged() {
        updateSession();
        if (current() != null) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        }
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerChanged();
    }
}
