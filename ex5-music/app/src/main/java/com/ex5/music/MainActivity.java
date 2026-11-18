package com.ex5.music;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements PlayerService.Listener {
    private static final int REQ_TREE = 1;
    private static final int REQ_FILES = 2;
    private static final int REQ_PERMISSION = 3;
    private static final String PREF_TREE = "tree_uri";

    private static final int BG = Color.rgb(16, 16, 20);
    private static final int CARD = Color.rgb(34, 34, 40);
    private static final int ACCENT = Color.rgb(250, 45, 72);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(170, 170, 180);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Track> library = new ArrayList<>();

    private PlayerService player;
    private ArrayAdapter<Track> adapter;
    private EditText linkBox;
    private TextView appleStatus;
    private TextView libraryStatus;
    private TextView nowTitle;
    private TextView nowFormat;
    private TextView timeText;
    private SeekBar seek;
    private Button playButton;
    private boolean userSeeking;
    private Intent pendingIntent;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            player = ((PlayerService.LocalBinder) service).service();
            player.addListener(MainActivity.this);
            if (pendingIntent != null) {
                handleIntent(pendingIntent);
                pendingIntent = null;
            }
            onPlayerChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            player = null;
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();

        Intent svc = new Intent(this, PlayerService.class);
        startService(svc);
        bindService(svc, connection, Context.BIND_AUTO_CREATE);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }

        pendingIntent = getIntent();
        loadSavedFolder();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (player != null) handleIntent(intent); else pendingIntent = intent;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAppleStatus();
        main.post(ticker);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.removeListener(this);
        unbindService(connection);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        String link = linkBox != null ? linkBox.getText().toString() : "";
        buildUi();
        linkBox.setText(link);
        refreshAppleStatus();
        adapter.notifyDataSetChanged();
        onPlayerChanged();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        boolean wide = getResources().getConfiguration().screenWidthDp
                > getResources().getConfiguration().screenHeightDp;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Left / top: Apple Music.
        LinearLayout apple = card();
        apple.addView(label("Apple Music", 26, TEXT, true));
        appleStatus = label("", 15, MUTED, false);
        apple.addView(appleStatus);
        apple.addView(bigButton("▶  เปิดแอป Apple Music", ACCENT, v -> openAppleMusic()));

        linkBox = new EditText(this);
        linkBox.setHint("วางลิงก์ music.apple.com/…");
        linkBox.setHintTextColor(MUTED);
        linkBox.setTextColor(TEXT);
        linkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        linkBox.setSingleLine(true);
        linkBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        linkBox.setOnEditorActionListener((v, actionId, event) -> {
            openLink();
            return true;
        });
        apple.addView(linkBox, matchWrap(dp(12)));

        LinearLayout linkRow = row();
        linkRow.addView(bigButton("เปิดลิงก์", CARD_BUTTON, v -> openLink()), weight());
        linkRow.addView(bigButton("วางจากคลิปบอร์ด", CARD_BUTTON, v -> pasteLink()), weight());
        apple.addView(linkRow, matchWrap(dp(8)));

        apple.addView(label("Lossless ของ Apple Music เล่นในแอป Apple Music: "
                + "Settings → Audio Quality → Lossless Audio", 13, MUTED, false), matchWrap(dp(12)));

        // Right / bottom: local lossless player.
        LinearLayout local = card();
        local.addView(label("เล่นไฟล์ Lossless (USB / เครื่อง)", 22, TEXT, true));
        libraryStatus = label("ยังไม่ได้เลือกเพลง", 14, MUTED, false);
        local.addView(libraryStatus);

        LinearLayout pickRow = row();
        pickRow.addView(bigButton("เลือกโฟลเดอร์", CARD_BUTTON, v -> pickFolder()), weight());
        pickRow.addView(bigButton("เลือกไฟล์", CARD_BUTTON, v -> pickFiles()), weight());
        pickRow.addView(bigButton("สแกนทั้งหมด", CARD_BUTTON, v -> scanMediaStore()), weight());
        local.addView(pickRow, matchWrap(dp(8)));

        nowTitle = label("—", 20, TEXT, true);
        nowTitle.setSingleLine(true);
        local.addView(nowTitle, matchWrap(dp(12)));
        nowFormat = label("", 15, ACCENT, true);
        local.addView(nowFormat);

        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null) timeText.setText(Links.formatTime(p) + " / " + Links.formatTime(player.duration()));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (player != null) player.seekTo(s.getProgress());
            }
        });
        local.addView(seek, matchWrap(dp(8)));
        timeText = label("0:00 / 0:00", 14, MUTED, false);
        local.addView(timeText);

        LinearLayout controls = row();
        controls.addView(bigButton("⏮", CARD_BUTTON, v -> { if (player != null) player.previous(); }), weight());
        playButton = bigButton("▶", ACCENT, v -> { if (player != null) player.toggle(); });
        controls.addView(playButton, weight());
        controls.addView(bigButton("⏭", CARD_BUTTON, v -> { if (player != null) player.next(); }), weight());
        local.addView(controls, matchWrap(dp(8)));

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<Track>(this, android.R.layout.simple_list_item_1, library) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                tv.setMinHeight(dp(52));
                boolean current = player != null && player.current() != null
                        && player.current().uri.equals(getItem(position).uri);
                tv.setTextColor(current ? ACCENT : TEXT);
                return tv;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (player != null) player.setQueue(new ArrayList<>(library), position);
        });
        local.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView appleScroll = new ScrollView(this);
        appleScroll.addView(apple);
        if (wide) {
            root.addView(appleScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f);
            lp.leftMargin = dp(16);
            root.addView(local, lp);
        } else {
            root.addView(appleScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            lp.topMargin = dp(16);
            root.addView(local, lp);
        }
        setContentView(root);
    }

    private static final int CARD_BUTTON = Color.rgb(58, 58, 66);

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(20), dp(20), dp(20));
        l.setBackground(rounded(CARD));
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button bigButton(String text, int color, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setMinHeight(dp(64));
        b.setBackground(rounded(color));
        b.setOnClickListener(click);
        return b;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- Apple Music

    private boolean appleMusicInstalled() {
        return getPackageManager().getLaunchIntentForPackage(Links.APPLE_MUSIC_PACKAGE) != null;
    }

    private void refreshAppleStatus() {
        if (appleStatus == null) return;
        appleStatus.setText(appleMusicInstalled()
                ? "พบแอป Apple Music ในเครื่องนี้"
                : "ยังไม่ได้ติดตั้งแอป Apple Music บนจอนี้ (ลิงก์จะเปิดในเบราว์เซอร์)");
    }

    private void openAppleMusic() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Links.APPLE_MUSIC_PACKAGE);
        if (launch != null) {
            startActivity(launch);
            return;
        }
        toast("ไม่พบแอป Apple Music บนจอนี้ — กำลังเปิดหน้าดาวน์โหลด");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + Links.APPLE_MUSIC_PACKAGE)));
        } catch (ActivityNotFoundException e) {
            openInBrowser("https://music.apple.com/");
        }
    }

    private void pasteLink() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        if (clip == null || clip.getItemCount() == 0) {
            toast("คลิปบอร์ดว่าง");
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        linkBox.setText(text);
        openLink();
    }

    private void openLink() {
        String link = Links.findAppleMusicLink(linkBox.getText().toString());
        if (link == null) {
            toast("ไม่ใช่ลิงก์ Apple Music (ต้องเป็น music.apple.com/…)");
            return;
        }
        linkBox.setText(link);
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
        if (appleMusicInstalled()) {
            view.setPackage(Links.APPLE_MUSIC_PACKAGE);
            try {
                startActivity(view);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Fall back to any handler below.
            }
        }
        openInBrowser(link);
    }

    private void openInBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast("ไม่มีแอปที่เปิดลิงก์นี้ได้บนจอนี้");
        }
    }

    // ---------------------------------------------------------------- incoming intents

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            String link = Links.findAppleMusicLink(text);
            if (link != null) {
                linkBox.setText(link);
                openLink();
            } else {
                toast("ข้อความที่แชร์มาไม่มีลิงก์ Apple Music");
            }
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            Uri uri = intent.getData();
            Track t = new Track(uri, displayName(uri));
            library.clear();
            library.add(t);
            adapter.notifyDataSetChanged();
            libraryStatus.setText("เปิดไฟล์ 1 ไฟล์");
            player.setQueue(new ArrayList<>(library), 0);
        }
        // Consume so a rotation does not replay it.
        intent.setAction(Intent.ACTION_MAIN);
    }

    // ---------------------------------------------------------------- local library

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            toast("จอนี้ไม่มีตัวเลือกโฟลเดอร์ — ลองปุ่ม \"สแกนทั้งหมด\"");
        }
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_FILES);
        } catch (ActivityNotFoundException e) {
            toast("จอนี้ไม่มีตัวเลือกไฟล์ — ลองปุ่ม \"สแกนทั้งหมด\"");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (requestCode == REQ_TREE && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, flags);
            } catch (SecurityException ignored) {
                // Still readable for this session.
            }
            prefs().edit().putString(PREF_TREE, tree.toString()).apply();
            scanTree(tree);
        } else if (requestCode == REQ_FILES) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            List<Track> tracks = new ArrayList<>();
            for (Uri u : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(u, flags);
                } catch (SecurityException ignored) {
                    // Not persistable; fine for now.
                }
                tracks.add(new Track(u, displayName(u)));
            }
            showLibrary(tracks, "เลือกไว้ " + tracks.size() + " ไฟล์");
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("ex5music", MODE_PRIVATE);
    }

    private void loadSavedFolder() {
        String saved = prefs().getString(PREF_TREE, null);
        if (saved == null) return;
        Uri tree = Uri.parse(saved);
        boolean stillGranted = false;
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isReadPermission()) stillGranted = true;
        }
        if (stillGranted) scanTree(tree);
    }

    private void scanTree(Uri tree) {
        libraryStatus.setText("กำลังสแกนโฟลเดอร์…");
        ContentResolver cr = getContentResolver();
        new Thread(() -> {
            List<Track> found = new ArrayList<>();
            String error = null;
            try {
                ArrayDeque<String> dirs = new ArrayDeque<>();
                dirs.add(DocumentsContract.getTreeDocumentId(tree));
                while (!dirs.isEmpty() && found.size() < 20000) {
                    String docId = dirs.poll();
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
                    try (Cursor c = cr.query(children, new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                        if (c == null) continue;
                        while (c.moveToNext()) {
                            String id = c.getString(0);
                            String name = c.getString(1);
                            String mime = c.getString(2);
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                                dirs.add(id);
                            } else if (Links.isAudioName(name) || (mime != null && mime.startsWith("audio/"))) {
                                found.add(new Track(DocumentsContract.buildDocumentUriUsingTree(tree, id), name));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            String err = error;
            main.post(() -> {
                if (err != null && found.isEmpty()) {
                    libraryStatus.setText("สแกนไม่ได้: " + err + " — กดเลือกโฟลเดอร์ใหม่");
                } else {
                    showLibrary(found, "พบ " + found.size() + " เพลง");
                }
            });
        }).start();
    }

    private void scanMediaStore() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_PERMISSION);
            return;
        }
        libraryStatus.setText("กำลังสแกนเพลงทั้งหมด…");
        ContentResolver cr = getContentResolver();
        new Thread(() -> {
            List<Track> found = new ArrayList<>();
            List<Uri> collections = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 29) {
                for (String volume : MediaStore.getExternalVolumeNames(this)) {
                    collections.add(MediaStore.Audio.Media.getContentUri(volume));
                }
            } else {
                collections.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
            }
            for (Uri collection : collections) {
                try (Cursor c = cr.query(collection, new String[]{
                        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME}, null, null, null)) {
                    if (c == null) continue;
                    while (c.moveToNext()) {
                        found.add(new Track(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1)));
                    }
                } catch (Exception ignored) {
                    // A removed USB volume can fail; keep the others.
                }
            }
            main.post(() -> showLibrary(found, "พบ " + found.size() + " เพลงในเครื่องและ USB"));
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanMediaStore();
            } else {
                toast("ต้องอนุญาตให้อ่านไฟล์เพลงก่อน หรือใช้ปุ่ม \"เลือกโฟลเดอร์\"");
            }
        }
    }

    private void showLibrary(List<Track> tracks, String status) {
        Collections.sort(tracks, (a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        library.clear();
        library.addAll(tracks);
        adapter.notifyDataSetChanged();
        int lossless = 0;
        for (Track t : tracks) if (Links.isLosslessName(t.name)) lossless++;
        libraryStatus.setText(String.format(Locale.ROOT, "%s (◆ Lossless %d)", status, lossless));
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
            // Fall back to the path.
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : uri.toString();
    }

    // ---------------------------------------------------------------- player callbacks

    @Override
    public void onPlayerChanged() {
        if (player == null || nowTitle == null) return;
        Track t = player.current();
        nowTitle.setText(t != null ? t.name : "—");
        String err = player.error();
        nowFormat.setText(err.isEmpty() ? player.formatInfo() : err);
        playButton.setText(player.isPlaying() ? "⏸" : "▶");
        adapter.notifyDataSetChanged();
        updateProgress();
    }

    private void updateProgress() {
        if (player == null || seek == null || userSeeking) return;
        int dur = player.duration();
        seek.setMax(Math.max(dur, 1));
        seek.setProgress(player.position());
        timeText.setText(Links.formatTime(player.position()) + " / " + Links.formatTime(dur));
    }
}
